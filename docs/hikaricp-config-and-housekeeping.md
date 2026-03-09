# HikariCP Configuration and Housekeeping

---

## 1. Configuration Defaults

Source: `com.zaxxer.hikari.HikariConfig` (1269 lines)

```java
// Timeout defaults
CONNECTION_TIMEOUT  = 30_000ms    // max wait for connection from pool
VALIDATION_TIMEOUT  = 5_000ms     // max wait for connection.isValid()
IDLE_TIMEOUT        = 600_000ms   // 10 minutes
MAX_LIFETIME        = 1_800_000ms // 30 minutes
DEFAULT_KEEPALIVE   = 120_000ms   // 2 minutes
SOFT_TIMEOUT_FLOOR  = 250ms       // minimum connectionTimeout

// Pool size defaults
DEFAULT_POOL_SIZE   = 10          // maximumPoolSize
minIdle             = -1          // -1 means "same as maximumPoolSize"
```

### Runtime-Mutable Properties (via MXBean)

These can be changed while the pool is running:
- `catalog`
- `connectionTimeout`
- `validationTimeout`
- `idleTimeout`
- `leakDetectionThreshold`
- `maxLifetime`
- `maximumPoolSize`
- `minimumIdle`
- `username` / `password`

### Sealed Properties (immutable after pool start)

- `jdbcUrl`, `dataSourceClassName`, `driverClassName`
- `connectionInitSql`, `connectionTestQuery`
- `isAutoCommit`, `isReadOnly`, `transactionIsolation`
- `schema`, `dataSource`, `threadFactory`, `scheduledExecutor`

### Seal Mechanism

```java
private volatile boolean sealed;

// Called after pool construction
void seal() { this.sealed = true; }

// Setters check:
if (sealed) throw new IllegalStateException("The configuration of the pool is sealed once started.");
```

---

## 2. Configuration Validation

`HikariConfig.validate()` is called before pool creation. Key validations:

```
- maxLifetime must be >= 30_000ms (30s), warns if < 120_000ms (2m)
- keepaliveTime:
    - if > 0, must be < maxLifetime
    - must be >= 30_000ms (30s)
- idleTimeout:
    - if > 0 and minIdle < maxPoolSize, must be >= 10_000ms (10s)
    - capped to maxLifetime (cannot exceed it)
    - if minIdle == maxPoolSize, idleTimeout is ignored (set to 0)
- connectionTimeout >= SOFT_TIMEOUT_FLOOR (250ms), or 0 (→ Integer.MAX_VALUE = infinite wait)
- validationTimeout >= SOFT_TIMEOUT_FLOOR (250ms) and < connectionTimeout
- maxPoolSize >= 1
- minIdle: if -1, set to maxPoolSize
- One of: dataSource, dataSourceClassName, or jdbcUrl must be specified
```

### Pool Name Auto-Generation

If no poolName is specified, one is generated:
```java
poolName = "HikariPool-" + generatePoolNumber();
// where generatePoolNumber produces a random 10-character alphanumeric string
```

---

## 3. Housekeeping — HouseKeeper Task

Source: `HikariPool.HouseKeeper` (inner class, ~60 lines)

Scheduled at `scheduleWithFixedDelay(houseKeeper, 100ms, 30_000ms, MILLISECONDS)`.

### Algorithm

```java
public void run() {
    // 1. Refresh runtime-mutable config
    connectionTimeout = config.getConnectionTimeout();
    validationTimeout = config.getValidationTimeout();
    leakTaskFactory.updateLeakDetectionThreshold(config.getLeakDetectionThreshold());
    if (config.getCatalog() != null && !config.getCatalog().equals(catalog)) {
        catalog = config.getCatalog();  // via AtomicReferenceFieldUpdater
    }

    final var idleTimeout = config.getIdleTimeout();
    final var now = currentTime();

    // 2. Retrograde clock detection
    if (now + 128ms < previous + housekeepingPeriod) {
        // Clock went backwards (NTP correction)
        // → soft-evict ALL connections
        softEvictConnections();
        return;
    }
    if (now > previous + 1.5 * housekeepingPeriod) {
        // Housekeeper ran much later than expected
        // → log thread starvation warning
    }

    previous = now;

    // 3. Idle timeout eviction
    if (idleTimeout > 0 && minIdle < maxPoolSize) {
        var notInUse = connectionBag.values(STATE_NOT_IN_USE);
        var maxToRemove = notInUse.size() - minIdle;
        for (PoolEntry entry : notInUse) {
            if (maxToRemove > 0
                && elapsedMillis(entry.lastAccessed, now) > idleTimeout
                && connectionBag.reserve(entry)) {
                closeConnection(entry, "(connection has passed idleTimeout)");
                maxToRemove--;
            }
        }
    }

    // 4. Fill pool back to minimum
    fillPool(true);
}
```

### Key Design Details

1. **Clock anomaly handling**: HikariCP detects both retrograde (NTP backward jump) and forward
   (thread starvation / system sleep) clock changes. Retrograde → evict all. Forward → just warn.

2. **Idle eviction ordering**: `values(STATE_NOT_IN_USE)` returns entries reversed, so oldest-idle
   connections are evicted first.

3. **Reserve-before-close**: The housekeeper must `reserve()` an entry (CAS NOT_IN_USE → RESERVED)
   before closing it. This prevents racing with a concurrent `borrow()`.

---

## 4. Pool Fill Strategy

```java
private synchronized void fillPool(final boolean isAfterAdd) {
    final var idle = getIdleConnections();
    final var shouldAdd = getTotalConnections() < maxPoolSize && idle < minIdle;

    if (shouldAdd) {
        final var countToAdd = minIdle - idle;
        for (int i = 0; i < countToAdd; i++) {
            addConnectionExecutor.submit(poolEntryCreator);
        }
    }
}
```

- **Synchronized**: Prevents multiple threads from submitting duplicate fill tasks
- **Non-blocking**: Actual connection creation happens asynchronously on `addConnectionExecutor`
- `shouldContinueCreating()` double-checks conditions before each connection attempt

---

## 5. Leak Detection

### ProxyLeakTask

When `leakDetectionThreshold > 0`, each borrowed connection gets a scheduled task:

```java
class ProxyLeakTask implements Runnable {
    void schedule(ScheduledExecutorService exec, long threshold) {
        scheduledFuture = exec.schedule(this, threshold, MILLISECONDS);
    }

    public void run() {
        isLeaked = true;
        // Log warning with stack trace of where connection was borrowed
        LOGGER.warn("Connection leak detected for {} on thread {}", connectionName, threadName, exception);
    }

    void cancel() {
        scheduledFuture.cancel(false);
        if (isLeaked) {
            LOGGER.info("Previously leaked connection was returned to pool (unleaked)");
        }
    }
}
```

The `exception` field is an `Exception` created at borrow time — its stack trace shows where
the connection was acquired. When `close()` is called, the task is cancelled.

### ProxyLeakTaskFactory

If `leakDetectionThreshold == 0`, returns `ProxyLeakTask.NO_LEAK` (a singleton with no-op methods),
so there's zero overhead when leak detection is disabled.

---

## 6. Pool Suspend/Resume

Controlled by `SuspendResumeLock`, which is a `Semaphore` with 10,000 permits.

```java
// Normal operation: each getConnection() acquires 1 permit (out of 10,000)
acquire() → semaphore.tryAcquire()  // fast, non-blocking

// Suspend: drain all 10,000 permits
suspend() → semaphore.acquireUninterruptibly(10_000)
// All subsequent getConnection() calls block (no permits available)

// Resume: release all 10,000 permits
resume() → semaphore.release(10_000)
// Blocked threads are unblocked
```

When suspension is disabled (default), `FAUX_LOCK` is used — all methods are no-ops,
and the JIT can eliminate the call entirely.

---

## 7. Pool Shutdown

```java
public synchronized void shutdown() throws InterruptedException {
    poolState = POOL_SHUTDOWN;

    // 1. Cancel housekeeping
    houseKeeperTask.cancel(false);

    // 2. Mark all connections for eviction
    softEvictConnections();

    // 3. Wait for addConnectionExecutor to finish
    addConnectionExecutor.shutdown();
    addConnectionExecutor.awaitTermination(loginTimeout, SECONDS);

    // 4. Shutdown housekeeping executor
    houseKeepingExecutorService.shutdownNow();

    // 5. Close the bag
    connectionBag.close();

    // 6. Force-close active connections
    var assassinExecutor = new ThreadPoolExecutor(...);
    do {
        abortActiveConnections(assassinExecutor);
        softEvictConnections();
    } while (getTotalConnections() > 0 && elapsed < 10s);

    // 7. Cleanup
    shutdownNetworkTimeoutExecutor();
    closeConnectionExecutor.shutdown();
    handleMBeans(this, false);
    metricsTracker.close();
}
```

### Active Connection Abort

```java
private void abortActiveConnections(ExecutorService assassinExecutor) {
    for (var poolEntry : connectionBag.values(STATE_IN_USE)) {
        Connection connection = poolEntry.close();
        connection.abort(assassinExecutor);  // JDBC4 async abort
        connectionBag.remove(poolEntry);
    }
}
```

---

## 8. Relevance to sqleicht

### What to Adopt
- Housekeeping pattern with 30s period
- Idle timeout eviction (reserve → close)
- Random variance on maxLifetime (prevent thundering herd)
- fillPool strategy (async, demand-driven)
- Config sealing after pool start
- Leak detection via scheduled tasks

### What to Simplify
- No clock anomaly detection needed (SQLite is local, no network)
- No suspend/resume (YAGNI for embedded database)
- No JNDI, no DataSource abstraction
- No MXBean (consider simple metrics interface instead)
- Keepalive is less critical for SQLite (no network to drop)

### Virtual-Thread Adaptations
- `synchronized` in `fillPool()` and `shutdown()` → use `ReentrantLock`
- Platform threads for housekeeping → virtual thread scheduled executor
- Leak detection timer → virtual thread scheduled executor
