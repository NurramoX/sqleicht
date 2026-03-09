# PoolEntry and Connection Lifecycle

Source: `com.zaxxer.hikari.pool.PoolEntry` (209 lines)

---

## 1. PoolEntry Structure

```java
final class PoolEntry implements IConcurrentBagEntry {
    // State management (CAS via AtomicIntegerFieldUpdater)
    private static final AtomicIntegerFieldUpdater<PoolEntry> stateUpdater;
    private volatile int state = 0;  // NOT_IN_USE initially

    // Connection & timing
    Connection connection;           // the raw JDBC connection
    long lastAccessed;               // timestamp of last return to pool
    long lastBorrowed;               // timestamp of last borrow (for metrics)

    // Flags
    private volatile boolean evict;  // marked for eviction

    // Scheduled tasks
    private volatile ScheduledFuture<?> endOfLife;   // MaxLifetimeTask
    private volatile ScheduledFuture<?> keepalive;   // KeepaliveTask

    // Open statement tracking
    private final FastList<Statement> openStatements;

    // Pool reference (for recycling)
    private final HikariPool hikariPool;

    // Cached defaults
    private final boolean isReadOnly;
    private final boolean isAutoCommit;
}
```

## 2. Why AtomicIntegerFieldUpdater (not AtomicInteger)?

`AtomicIntegerFieldUpdater.newUpdater(PoolEntry.class, "state")` operates on a plain
`volatile int` field instead of requiring a separate `AtomicInteger` object.

Benefits:
- **16 bytes saved per PoolEntry** (no AtomicInteger object header + padding)
- **Better cache locality** — state is in the same cache line as other PoolEntry fields
- Same CAS semantics as `AtomicInteger`

This is a micro-optimization that matters when you have hundreds of pool entries being
CAS'd millions of times per second.

## 3. Connection Lifecycle States

```
                 ┌─── CREATED ───┐
                 │                │
                 ▼                │
           ┌──────────┐    [createPoolEntry()]
           │ NOT_IN_USE│◄─────────┘
           └─────┬─────┘
        borrow() │ ▲ requite()
        (CAS)    │ │ (set)
                 ▼ │
           ┌──────────┐
           │  IN_USE   │
           └─────┬─────┘
                 │
        ┌────────┴────────┐
        │                 │
   close()            evict/dead
   (recycle)          (closeConnection)
        │                 │
        ▼                 ▼
   NOT_IN_USE          REMOVED
                    (connection closed)
```

### Alternative Path: Housekeeping

```
NOT_IN_USE ──reserve()──> RESERVED ──remove()──> REMOVED
                              │
                        unreserve()
                              │
                              ▼
                         NOT_IN_USE
```

## 4. Key Methods

### recycle()
Called when user closes the proxy connection. Records timestamp, then delegates to
`HikariPool.recycle()` which either closes (if evicted) or requits to the bag.

```java
void recycle() {
    if (connection != null) {
        this.lastAccessed = currentTime();
        hikariPool.recycle(this);
    }
}
```

### close()
Called when removing from pool. Cancels scheduled tasks, nulls out fields, returns
the raw connection for closing.

```java
Connection close() {
    // Cancel end-of-life task
    var eol = endOfLife;
    if (eol != null && !eol.isDone() && !eol.cancel(false)) {
        LOGGER.warn("...");
    }
    // Cancel keepalive task
    var ka = keepalive;
    if (ka != null && !ka.isDone() && !ka.cancel(false)) {
        LOGGER.warn("...");
    }
    var con = connection;
    connection = null;
    endOfLife = null;
    keepalive = null;
    return con;
}
```

### createProxyConnection()
Creates the Javassist-generated proxy around the raw connection, passing along the
open statements list and leak detection task.

### markEvicted() / isMarkedEvicted()
Soft eviction flag. Once set, the connection will be closed next time it's returned
to the pool (during `recycle()`).

## 5. Implications for sqleicht

### What to Adopt
- `AtomicIntegerFieldUpdater` for state management (same memory/perf benefits)
- Timestamp tracking (lastAccessed, lastBorrowed)
- Eviction flag pattern (mark now, close when returned)
- Scheduled tasks for maxLifetime and keepalive with random variance

### What's Different
- No `FastList<Statement>` — we don't have JDBC Statement proxying
- No `ProxyConnection` — our wrapper is direct
- Our "connection" is a `MemorySegment` (sqlite3 pointer), not `java.sql.Connection`
- No `ScheduledFuture` for end-of-life — SQLite connections don't have network timeouts,
  but we still want maxLifetime for memory/WAL hygiene
