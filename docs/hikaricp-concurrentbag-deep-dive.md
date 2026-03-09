# ConcurrentBag Deep Dive

The `ConcurrentBag` is the most performance-critical component of HikariCP and the primary
reason it outperforms other connection pools. This document captures its full implementation
for reference when building sqleicht's pool.

Source: `com.zaxxer.hikari.util.ConcurrentBag` (414 lines)

---

## 1. Type Signature

```java
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable
```

The bag stores items that implement `IConcurrentBagEntry`, which provides CAS-based state management:

```java
public interface IConcurrentBagEntry {
    int STATE_NOT_IN_USE = 0;
    int STATE_IN_USE = 1;
    int STATE_REMOVED = -1;
    int STATE_RESERVED = -2;

    boolean compareAndSet(int expectState, int newState);
    void setState(int newState);
    int getState();
}
```

## 2. Internal Data Structures

```java
CopyOnWriteArrayList<T> sharedList;           // canonical collection of ALL entries
ThreadLocal<List<Object>> threadLocalList;     // per-thread fast cache (max 16)
SynchronousQueue<T> handoffQueue;              // fair, for waiter notification
AtomicInteger waiters;                         // number of threads blocked waiting
IBagStateListener listener;                    // callback → HikariPool.addBagItem()
volatile boolean closed;
boolean useWeakThreadLocals;                   // true if custom ClassLoader detected
```

### Why CopyOnWriteArrayList?

- Reads (iteration during borrow/housekeeping) vastly outnumber writes (add/remove connections)
- Iteration is lock-free — no contention during the shared-list scan
- Write cost (copy entire array) is acceptable because connections are rarely added/removed

### Why SynchronousQueue (not LinkedTransferQueue)?

- Direct handoff: a returning connection goes straight to a waiting thread
- Fair mode (`true` in constructor): prevents starvation
- No internal buffering — items aren't "stored" in the queue

### Why ThreadLocal?

- Eliminates contention entirely in the fast path
- LIFO order: most recently returned connection is borrowed first (better cache locality)
- Limited to 16 entries per thread to prevent memory bloat

### WeakReference Mode

If a custom ClassLoader is detected (classloader != system classloader), ThreadLocal entries are
wrapped in `WeakReference<T>` to prevent class-unloading leaks in application servers.

## 3. Borrow — Full Implementation

```java
public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException {
    // STEP 1: ThreadLocal fast path (zero contention)
    final var list = threadLocalList.get();
    for (var i = list.size() - 1; i >= 0; i--) {
        final var entry = list.remove(i);
        final T bagEntry = useWeakThreadLocals
            ? ((WeakReference<T>) entry).get()
            : (T) entry;
        if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;  // ← FAST PATH: no atomic contention, just CAS on entry
        }
    }

    // STEP 2: Shared list scan + handoff queue
    final var waiting = waiters.incrementAndGet();
    try {
        // Scan shared list — CAS each entry
        for (T bagEntry : sharedList) {
            if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                if (waiting > 1) {
                    listener.addBagItem(waiting - 1);  // may have stolen another waiter's connection
                }
                return bagEntry;
            }
        }

        // No idle connections found — ask pool to create one
        listener.addBagItem(waiting);

        // STEP 3: Wait on handoff queue
        timeout = timeUnit.toNanos(timeout);
        do {
            final var start = currentTime();
            final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
            if (bagEntry == null || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                return bagEntry;  // null = timeout, non-null = got a connection
            }
            timeout -= elapsedNanos(start);
        } while (timeout > 10_000);  // 10µs minimum remaining

        return null;  // timed out
    } finally {
        waiters.decrementAndGet();
    }
}
```

### Performance Analysis

| Step | Contention | Latency |
|------|-----------|---------|
| ThreadLocal scan | **None** — thread-local data only | ~5-10ns |
| CAS on entry | **Per-entry** — but typically first entry succeeds | ~20-50ns |
| SharedList scan | **Low** — CAS only, no locks | ~100-500ns |
| HandoffQueue poll | **Blocking** — parks thread | µs to ms |

In the common case (thread reuses same connection), borrow completes in **<50ns**.

## 4. Requite (Return) — Full Implementation

```java
public void requite(final T bagEntry) {
    // Make entry available immediately
    bagEntry.setState(STATE_NOT_IN_USE);  // volatile write, not CAS

    // Try to hand off to a waiting thread
    for (int i = 1, waiting = waiters.get(); waiting > 0; i++, waiting = waiters.get()) {
        if (bagEntry.getState() != STATE_NOT_IN_USE || handoffQueue.offer(bagEntry)) {
            return;  // handed off successfully, or someone else grabbed it
        }
        // Spin/yield strategy:
        else if ((i & 0xff) == 0xff || (waiting > 1 && i % waiting == 0)) {
            parkNanos(MICROSECONDS.toNanos(10));  // brief park every 256 iterations
        } else {
            Thread.yield();
        }
    }

    // No waiters — cache in ThreadLocal for next borrow
    final var threadLocalEntries = this.threadLocalList.get();
    if (threadLocalEntries.size() < 16) {
        threadLocalEntries.add(useWeakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry);
    }
}
```

### Key Design Decisions

1. **`setState` not `compareAndSet`**: Requite uses a plain volatile write because only the
   owning thread should be returning the entry. No CAS needed.

2. **Spin-then-park**: The handoff loop spins with `Thread.yield()`, parking briefly every 256
   iterations. This balances latency (spinning is fast) with CPU usage (parking is efficient).

3. **ThreadLocal limit of 16**: Prevents memory bloat. If a thread opens many connections
   simultaneously, excess returns just don't cache.

## 5. Add — Full Implementation

```java
public void add(final T bagEntry) {
    if (closed) {
        throw new IllegalStateException("ConcurrentBag has been closed");
    }

    sharedList.add(bagEntry);  // CopyOnWriteArrayList.add() is thread-safe

    // Try to hand off to a waiter
    while (waiters.get() > 0
           && bagEntry.getState() == STATE_NOT_IN_USE
           && !handoffQueue.offer(bagEntry)) {
        Thread.yield();
    }
}
```

## 6. Remove

```java
public boolean remove(final T bagEntry) {
    // Only IN_USE or RESERVED entries can be removed
    if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED)
        && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED)
        && !closed) {
        LOGGER.warn("Attempt to remove an object that was not borrowed or reserved: {}", bagEntry);
        return false;
    }

    final var removed = sharedList.remove(bagEntry);
    threadLocalList.get().remove(bagEntry);
    return removed;
}
```

## 7. Reserve / Unreserve

Used by housekeeping to temporarily take a connection out of circulation (e.g., for validation
or idle-timeout eviction).

```java
public boolean reserve(final T bagEntry) {
    return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
}

public void unreserve(final T bagEntry) {
    if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
        // Same spin-handoff loop as requite
        // ...
    }
}
```

## 8. Snapshot Queries

```java
// Get entries in a specific state (e.g., for housekeeping)
public List<T> values(final int state) {
    return sharedList.stream()
        .filter(e -> e.getState() == state)
        .collect(Collectors.toList());
    // Note: reversed for LIFO-like eviction order
}

// Get all entries
public List<T> values() {
    return (List<T>) sharedList.clone();
}
```

## 9. Implications for sqleicht

### What to Keep
- The ThreadLocal + CAS fast path is brilliant and directly applicable
- The state machine (NOT_IN_USE, IN_USE, RESERVED, REMOVED) via AtomicIntegerFieldUpdater
- The SynchronousQueue handoff for waiting threads
- The IBagStateListener pattern for demand-driven connection creation

### What to Change for Virtual Threads
- `Thread.yield()` in spin loops is problematic with virtual threads (yields the virtual thread
  but doesn't help the carrier thread). Consider replacing with `Thread.onSpinWait()` or
  short `LockSupport.parkNanos()`.
- ThreadLocal usage: Virtual threads can have millions of instances, so ThreadLocal caches could
  consume significant memory. Consider using a smaller cache limit or a different caching
  strategy (e.g., scoped values if applicable).
- CopyOnWriteArrayList is fine — no locking concerns.
- SynchronousQueue.poll() uses `LockSupport.park()` internally, which is virtual-thread-friendly.

### What to Simplify
- WeakReference mode: We don't need to support application server classloader scenarios
- The entire Javassist proxy layer is unnecessary — we control our own API
