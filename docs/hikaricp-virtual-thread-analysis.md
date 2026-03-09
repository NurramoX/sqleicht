# HikariCP Virtual Thread Analysis

An audit of HikariCP's concurrency primitives and their behavior with Java virtual threads.
This directly informs sqleicht's design decisions.

---

## 1. The Problem with `synchronized`

Virtual threads use **continuation-based scheduling**. When a virtual thread enters a
`synchronized` block, it **pins its carrier thread** — the carrier cannot run other virtual
threads until the monitor is released. This defeats the purpose of virtual threads for I/O-bound
workloads.

HikariCP uses `synchronized` in several places. Each is analyzed below.

---

## 2. Audit of `synchronized` in HikariCP

### ProxyConnection (3 occurrences) — HIGH IMPACT

```java
// 1. Statement tracking
private synchronized <T extends Statement> T trackStatement(final T statement) {
    openStatements.add(statement);
    return statement;
}

// 2. Statement untracking
final synchronized void untrackStatement(final Statement statement) {
    openStatements.remove(statement);
}

// 3. Statement closing
private synchronized void closeStatements() {
    for (int i = 0; i < size ...) {
        statement.close();  // ← can block on I/O!
    }
    openStatements.clear();
}
```

**Impact**: HIGH. These are called on every statement create/close, which is the hottest path.
`closeStatements()` is especially bad because it does I/O while holding a monitor.

**sqleicht fix**: Use `ReentrantLock` or eliminate the need for synchronization entirely
(single-threaded access per connection is our design goal).

### HikariPool.fillPool() — MEDIUM IMPACT

```java
private synchronized void fillPool(final boolean isAfterAdd) { ... }
```

**Impact**: MEDIUM. Called by housekeeper and after connection close. Short duration (just
submits tasks), but can contend with shutdown.

**sqleicht fix**: `ReentrantLock`.

### HikariPool.shutdown() — LOW IMPACT

```java
public synchronized void shutdown() throws InterruptedException { ... }
```

**Impact**: LOW. Only called once during pool shutdown.

**sqleicht fix**: `ReentrantLock`.

### HikariPool.suspendPool() / resumePool() — LOW IMPACT

```java
public synchronized void suspendPool() { ... }
public synchronized void resumePool() { ... }
```

**Impact**: LOW. Rare operations. We're not implementing suspend/resume anyway.

### PoolEntryCreator.shouldContinueCreating() — MEDIUM IMPACT

```java
private synchronized boolean shouldContinueCreating() { ... }
```

**Impact**: MEDIUM. Called in a loop during connection creation. Could pin a virtual thread
during the entire creation loop.

**sqleicht fix**: Use volatile fields + CAS, or `ReentrantLock`.

### HikariDataSource.getConnection() (lazy init) — LOW IMPACT

```java
synchronized (this) {
    result = pool;
    if (result == null) {
        pool = result = new HikariPool(this);
    }
}
```

**Impact**: LOW. Only during first `getConnection()` call with default constructor.
Classic double-checked locking.

**sqleicht fix**: Use `ReentrantLock` or avoid lazy init entirely.

---

## 3. Other Concurrency Primitives — Assessment

### Safe for Virtual Threads

| Primitive | Used In | Why Safe |
|-----------|---------|----------|
| `AtomicIntegerFieldUpdater` (CAS) | PoolEntry state | Lock-free, no parking |
| `AtomicInteger` | ConcurrentBag.waiters | Lock-free |
| `AtomicReference` | PoolBase.lastConnectionFailure | Lock-free |
| `AtomicBoolean` | HikariDataSource.isShutdown | Lock-free |
| `AtomicReferenceFieldUpdater` | HouseKeeper.catalogUpdater | Lock-free |
| `volatile` fields | Various | No locking |
| `CopyOnWriteArrayList` | ConcurrentBag.sharedList | Read lock-free, write uses `ReentrantLock` internally |
| `SynchronousQueue` (fair) | ConcurrentBag.handoffQueue | Uses `LockSupport.park()` — virtual-thread-friendly |
| `Semaphore` | SuspendResumeLock | Uses `AbstractQueuedSynchronizer` — virtual-thread-friendly |
| `ScheduledThreadPoolExecutor` | Housekeeping | Thread-safe, no monitor pinning |

### Problematic for Virtual Threads

| Primitive | Used In | Problem |
|-----------|---------|---------|
| `synchronized` (6 sites) | See above | Pins carrier thread |
| `Thread.yield()` | ConcurrentBag requite/unreserve | With virtual threads, yields the virtual thread but carrier stays busy. Not harmful but not ideal. |

### Thread.yield() Analysis

Used in ConcurrentBag's spin loops:
```java
else {
    Thread.yield();
}
```

On platform threads: hints scheduler to let other threads run.
On virtual threads: unmounts the virtual thread briefly. The carrier thread is free to run
other virtual threads. This is actually fine, but could be replaced with `Thread.onSpinWait()`
for the spin case or `LockSupport.parkNanos()` for the wait case.

---

## 4. ThreadLocal Concerns

ConcurrentBag uses `ThreadLocal<List<Object>>` for per-thread connection caching.

### Platform Threads
- Typically 10-200 threads → 10-200 ThreadLocal entries
- Each caches up to 16 connection references
- Memory impact: negligible

### Virtual Threads
- Could have millions of virtual threads
- Each gets its own ThreadLocal
- If 100,000 virtual threads each have a ThreadLocal list → significant memory waste
- Most lists will be empty (connections returned before thread ends)

### sqleicht Mitigation Options

1. **Reduce cache size**: From 16 to 2-4
2. **Use ScopedValue** (Java 25 preview): Scoped values are designed for virtual threads
   but may not fit the "cache a connection" use case
3. **Skip ThreadLocal entirely**: Accept the performance hit of always scanning the shared list.
   With SQLite's fast local access, the CAS cost is less significant than for network databases.
4. **Global LRU cache with striping**: A striped lock-free cache that doesn't scale with thread count

---

## 5. sqleicht's Concurrency Strategy

Based on this analysis, sqleicht should:

### Use Instead of `synchronized`
- `ReentrantLock` everywhere mutual exclusion is needed
- `StampedLock` for read-heavy scenarios (e.g., pool state checks)
- CAS operations (`AtomicIntegerFieldUpdater`, `VarHandle`) for state transitions

### Keep from HikariCP
- `Semaphore` for connection acquisition limiting (already virtual-thread-safe)
- CAS-based state machine on pool entries
- `SynchronousQueue` for handoff between returning and waiting threads
- `CopyOnWriteArrayList` for the shared entry list
- `ScheduledThreadPoolExecutor` for housekeeping (or virtual-thread executor)

### Consider for Executors
```java
// Instead of platform thread pools:
Executors.newVirtualThreadPerTaskExecutor()

// For scheduled tasks:
// No built-in virtual-thread scheduler exists yet.
// Options:
// 1. ScheduledThreadPoolExecutor with 1 platform thread (schedule only, tasks run on virtual threads)
// 2. Custom scheduler using virtual threads + Thread.sleep()
```

### SQLite-Specific Consideration
SQLite has its own serialization mode (`SQLITE_OPEN_FULLMUTEX` vs `SQLITE_OPEN_NOMUTEX`).
Since we're using FFM and own the threading model, we should open connections with
`SQLITE_OPEN_NOMUTEX` and enforce single-threaded access per connection at the Java level
using our pool design. This avoids SQLite's internal mutex contention entirely.
