# HikariCP Architecture Reference

Reference documentation extracted from HikariCP source code (dev branch, commit bba167f0).
This serves as an architectural guide for sqleicht's pool implementation.

---

## 1. High-Level Component Overview

```
HikariDataSource (public entry point, implements DataSource)
  └── HikariConfig (configuration, implements HikariConfigMXBean)
  └── HikariPool (the actual pool, extends PoolBase, implements HikariPoolMXBean)
        ├── ConcurrentBag<PoolEntry>    -- lock-free connection storage
        ├── PoolEntry                   -- wraps a raw Connection + state
        ├── ProxyConnection             -- returned to callers, intercepts close()
        ├── ProxyLeakTaskFactory        -- schedules leak-detection timers
        ├── SuspendResumeLock           -- Semaphore-based pool suspend/resume
        ├── addConnectionExecutor       -- ThreadPoolExecutor (1 thread)
        ├── closeConnectionExecutor     -- ThreadPoolExecutor (1 thread)
        └── houseKeepingExecutorService -- ScheduledThreadPoolExecutor (1 thread)
```

### Class Responsibilities

| Class | Role |
|---|---|
| `HikariDataSource` | Public API. Implements `javax.sql.DataSource`. Delegates to `HikariPool`. Supports lazy init (default constructor) or eager init (config constructor). |
| `HikariConfig` | Holds all configuration. Implements `HikariConfigMXBean` for runtime-mutable properties. Sealed after pool starts. |
| `HikariPool` | The core pool. Extends `PoolBase`. Owns the `ConcurrentBag`, executors, and housekeeping. Implements `IBagStateListener` to react when connections are needed. |
| `PoolBase` | Abstract base for `HikariPool`. Handles raw connection creation, validation (`isConnectionDead`), state reset, DataSource initialization, JMX, and network timeout management. |
| `ConcurrentBag` | The heart of HikariCP's performance. A specialized concurrent collection that uses ThreadLocal + CAS for near-zero-contention borrowing. |
| `PoolEntry` | Wraps a `java.sql.Connection` plus pool metadata (timestamps, state, scheduled tasks). Implements `IConcurrentBagEntry` for CAS-based state management. |
| `ProxyConnection` | Abstract proxy around `Connection`. Intercepts `close()` to return to pool. Tracks dirty state bits. Generated subclass (`HikariProxyConnection`) is created at build time via Javassist. |
| `FastList` | Array-backed list with no bounds checking. Used for tracking open statements per connection. |
| `ClockSource` | Abstraction over `System.nanoTime()` / `System.currentTimeMillis()`. Mac uses millis; everything else uses nanos. |

---

## 2. Connection Lifecycle

### 2.1 Acquisition Flow (`getConnection()`)

```
User calls HikariDataSource.getConnection()
  │
  ├─ if fastPathPool != null → use directly (eager init path)
  │  else → double-checked locking to lazy-init the pool
  │
  └─ HikariPool.getConnection(connectionTimeout)
       │
       ├─ suspendResumeLock.acquire()      // Semaphore; no-op if suspension disabled
       │
       ├─ LOOP:
       │   ├─ connectionBag.borrow(timeout, MILLISECONDS)
       │   │    ├─ 1. Check ThreadLocal list (fast path, CAS-based)
       │   │    ├─ 2. Scan sharedList (CAS-based)
       │   │    ├─ 3. Notify listener (addBagItem) to create connections
       │   │    └─ 4. Poll handoffQueue (SynchronousQueue) with timeout
       │   │
       │   ├─ if null → timed out, break
       │   │
       │   ├─ if evicted OR (stale AND dead) → closeConnection(), continue loop
       │   │
       │   └─ else → record metrics, return poolEntry.createProxyConnection()
       │
       ├─ if loop exits without connection → throw SQLTransientConnectionException
       │
       └─ finally: suspendResumeLock.release()
```

### 2.2 Return Flow (`Connection.close()`)

```
User calls proxyConnection.close()
  │
  ├─ closeStatements()           // close any tracked open Statements
  │
  ├─ if connection not already closed:
  │    ├─ cancel leak task
  │    ├─ if dirty commit state && !autoCommit → rollback
  │    ├─ if dirty bits != 0 → resetConnectionState() (restore defaults)
  │    ├─ clearWarnings()
  │    └─ delegate = ClosedConnection.CLOSED_CONNECTION  (sentinel proxy)
  │
  └─ poolEntry.recycle()
       └─ HikariPool.recycle()
            ├─ metricsTracker.recordConnectionUsage()
            ├─ if evicted → closeConnection()
            └─ else → connectionBag.requite(poolEntry)
                  ├─ setState(NOT_IN_USE)
                  ├─ if waiters > 0 → try handoffQueue.offer()
                  └─ else → add to ThreadLocal list (up to 16)
```

### 2.3 Connection Creation

```
HikariPool.addBagItem(waiting)           // IBagStateListener callback
  └─ addConnectionExecutor.submit(poolEntryCreator)
       └─ PoolEntryCreator.call()
            ├─ while shouldContinueCreating():
            │    ├─ createPoolEntry()
            │    │    ├─ PoolBase.newPoolEntry()
            │    │    │    ├─ newConnection()
            │    │    │    │    ├─ dataSource.getConnection()
            │    │    │    │    └─ setupConnection() (readOnly, autoCommit, isolation, catalog, schema, initSql)
            │    │    │    └─ new PoolEntry(connection, pool, isReadOnly, isAutoCommit)
            │    │    │
            │    │    ├─ schedule MaxLifetimeTask (with random variance)
            │    │    └─ schedule KeepaliveTask (with random variance)
            │    │
            │    ├─ if success → connectionBag.add(poolEntry), break
            │    └─ if failure → backoff sleep (10ms, doubling up to 5s)
            │
            └─ shouldContinueCreating():
                 poolState == NORMAL
                 && totalConnections < maxPoolSize
                 && (idle < minIdle || waiters > idle)
```

### 2.4 Connection Eviction & Retirement

Connections are removed from the pool for these reasons:

1. **Max Lifetime** (`MaxLifetimeTask`): Scheduled per-connection with random variance (up to 25% of maxLifetime by default). Soft-evicts the connection (marks it, then closes if idle or waits for return).

2. **Idle Timeout** (`HouseKeeper`): Runs every 30s. If `idleTimeout > 0` and `minIdle < maxPoolSize`, evicts idle connections beyond `minIdle` that have exceeded `idleTimeout`.

3. **Dead Connection Detection**: On borrow, if the connection was last accessed more than 500ms ago, runs `isConnectionDead()` (JDBC4 `isValid()` or test query). Dead connections are closed.

4. **Keepalive** (`KeepaliveTask`): Periodically validates idle connections. Dead ones are evicted.

5. **User Eviction** (`evictConnection()`): Direct API call.

6. **Broken Connection** (`ProxyConnection.checkException()`): SQL state "08xxx" or known error codes → evict immediately.

---

## 3. ConcurrentBag — The Core Data Structure

### 3.1 Design Philosophy

ConcurrentBag is **the** reason HikariCP is fast. It avoids locks entirely in the common path:
- **ThreadLocal fast path**: Each thread caches recently-used entries in a ThreadLocal list. Borrowing checks this list first via CAS — no contention at all.
- **Shared list scan**: If ThreadLocal misses, scan the `CopyOnWriteArrayList` using CAS state transitions.
- **Handoff queue**: If no idle connections exist, wait on a `SynchronousQueue` for a returning connection.

### 3.2 State Machine

Each `IConcurrentBagEntry` (i.e., `PoolEntry`) has an int state managed via `AtomicIntegerFieldUpdater`:

```
NOT_IN_USE (0) ──CAS──> IN_USE (1)      // borrow
IN_USE (1)     ──set──> NOT_IN_USE (0)   // requite (return)
NOT_IN_USE (0) ──CAS──> RESERVED (-2)    // reserve (for housekeeping)
RESERVED (-2)  ──CAS──> NOT_IN_USE (0)   // unreserve
IN_USE (1)     ──CAS──> REMOVED (-1)     // remove (close)
RESERVED (-2)  ──CAS──> REMOVED (-1)     // remove (close)
```

All state transitions use CAS (compare-and-set) — no locks.

### 3.3 Key Fields

```java
CopyOnWriteArrayList<T> sharedList;       // all entries (regardless of state)
ThreadLocal<List<Object>> threadLocalList; // per-thread cache (up to 16)
SynchronousQueue<T> handoffQueue;          // for waiter notification
AtomicInteger waiters;                     // count of threads waiting
IBagStateListener listener;               // callback to HikariPool
boolean useWeakThreadLocals;              // true if custom classloader
```

### 3.4 Borrow Algorithm

```
1. Scan ThreadLocal list (LIFO, most recently returned first)
   - Remove entry, CAS NOT_IN_USE → IN_USE
   - If CAS succeeds → return immediately (ZERO contention)

2. Increment waiters count

3. Scan sharedList
   - CAS NOT_IN_USE → IN_USE on each entry
   - If CAS succeeds:
     - If other waiters exist → trigger addBagItem (might have stolen their connection)
     - Return the entry

4. Notify listener: addBagItem(waiting)

5. Poll handoffQueue with remaining timeout
   - Loop until timeout expires
   - On each poll result, CAS NOT_IN_USE → IN_USE
   - If CAS succeeds → return
   - If null → timed out, return null

6. Decrement waiters in finally block
```

### 3.5 Requite (Return) Algorithm

```
1. Set state to NOT_IN_USE (plain volatile write, not CAS)

2. If waiters > 0:
   - Try handoffQueue.offer(entry)
   - If entry was already borrowed by scanner → stop
   - Spin/yield while waiters exist

3. If no waiters: add to ThreadLocal list (up to 16 entries)
```

---

## 4. Proxy Layer (Javassist Code Generation)

HikariCP generates proxy classes at **build time** using Javassist bytecode manipulation. This is faster than `java.lang.reflect.Proxy` (no reflection overhead per call).

### 4.1 Generated Classes

| Base Class | Generated Class | Proxied Interface |
|---|---|---|
| `ProxyConnection` | `HikariProxyConnection` | `java.sql.Connection` |
| `ProxyStatement` | `HikariProxyStatement` | `java.sql.Statement` |
| `ProxyPreparedStatement` | `HikariProxyPreparedStatement` | `java.sql.PreparedStatement` |
| `ProxyCallableStatement` | `HikariProxyCallableStatement` | `java.sql.CallableStatement` |
| `ProxyResultSet` | `HikariProxyResultSet` | `java.sql.ResultSet` |
| `ProxyDatabaseMetaData` | `HikariProxyDatabaseMetaData` | `java.sql.DatabaseMetaData` |

### 4.2 Method Body Template

For most methods, the generated body is:
```java
{ try { return delegate.method($$); } catch (SQLException e) { throw checkException(e); } }
```

`checkException()` inspects the SQL state/error code and may evict the connection if it's broken.

### 4.3 ProxyFactory

`ProxyFactory` has static methods like `getProxyConnection(...)` whose bodies are replaced at build time to simply `return new HikariProxyConnection(...)`.

### 4.4 Relevance to sqleicht

We do NOT need Javassist — we're not proxying JDBC interfaces. Our "proxy" equivalent is simpler since we control the entire API. A plain wrapper class suffices.

---

## 5. Connection State Management

### 5.1 Dirty Bits

`ProxyConnection` tracks which connection properties the user changed via bitmask:

```java
DIRTY_BIT_READONLY   = 0b000001
DIRTY_BIT_AUTOCOMMIT = 0b000010
DIRTY_BIT_ISOLATION  = 0b000100
DIRTY_BIT_CATALOG    = 0b001000
DIRTY_BIT_NETTIMEOUT = 0b010000
DIRTY_BIT_SCHEMA     = 0b100000
```

On `close()`, only the dirty properties are reset — avoiding unnecessary round-trips to the database.

### 5.2 Commit State

If the connection is NOT in autoCommit mode and any statement was executed, `isCommitStateDirty` is set. On `close()`, an automatic `rollback()` is performed to avoid accidentally committing partial work.

---

## 6. Housekeeping

### 6.1 HouseKeeper Task

Runs every 30 seconds (configurable via `com.zaxxer.hikari.housekeeping.periodMs`).

**Actions:**
1. Refresh runtime-mutable config values (connectionTimeout, validationTimeout, leakDetectionThreshold, catalog)
2. Detect retrograde clock changes (NTP jumps) → soft-evict all connections
3. Detect thread starvation (housekeeper ran much later than expected) → log warning
4. If `idleTimeout > 0` and `minIdle < maxPoolSize`:
   - Get all NOT_IN_USE entries
   - Evict excess idle connections that have exceeded `idleTimeout`
5. `fillPool()` — ensure minimum idle connections are maintained

### 6.2 MaxLifetime Task

Scheduled per-connection at creation time. Fires after `maxLifetime - variance` milliseconds.
- Variance: random value up to `maxLifetime / varianceFactor` (default factor=4, so up to 25%)
- Purpose: prevent thundering herd of simultaneous connection expiry
- Calls `softEvictConnection()` → marks + closes if idle, or marks for eviction on next return

### 6.3 Keepalive Task

Scheduled per-connection at creation time with `scheduleWithFixedDelay`.
- Period: `keepaliveTime - variance` (variance up to 20%)
- Reserves the connection, validates it, unreserves or evicts

---

## 7. Thread Model

### 7.1 Executor Threads

| Executor | Type | Pool Size | Purpose |
|---|---|---|---|
| `addConnectionExecutor` | ThreadPoolExecutor | 1 core, 1 max | Creates new connections asynchronously |
| `closeConnectionExecutor` | ThreadPoolExecutor | 1 core, 1 max | Closes connections off the hot path |
| `houseKeepingExecutorService` | ScheduledThreadPoolExecutor | 1 core | Runs HouseKeeper, MaxLifetime, Keepalive tasks |

All use `allowCoreThreadTimeOut(true)` with 5s keepalive — threads die when pool is idle.

### 7.2 Virtual Thread Concerns

HikariCP was designed pre-virtual-threads. Key issues:
- `synchronized` in `ProxyConnection.trackStatement()`, `untrackStatement()`, `closeStatements()` — pins virtual threads
- `synchronized` in `HikariPool.fillPool()`, `HikariPool.shutdown()`, `PoolEntryCreator.shouldContinueCreating()`
- `SynchronousQueue.poll()` in ConcurrentBag is okay (park-based, not `synchronized`)
- `CopyOnWriteArrayList` iterations hold no lock, fine for virtual threads
- `Semaphore` (SuspendResumeLock) is fine for virtual threads

---

## 8. Configuration Surface

### 8.1 Essential Properties

| Property | Default | Description |
|---|---|---|
| `maximumPoolSize` | 10 | Max total connections (active + idle) |
| `minimumIdle` | = maximumPoolSize | Min idle connections to maintain |
| `connectionTimeout` | 30000ms | Max time to wait for a connection |
| `idleTimeout` | 600000ms (10min) | Max idle time before eviction (only if minIdle < maxPoolSize) |
| `maxLifetime` | 1800000ms (30min) | Max connection lifetime |
| `keepaliveTime` | 120000ms (2min) | How often to validate idle connections |
| `validationTimeout` | 5000ms | Max time for connection validation |
| `leakDetectionThreshold` | 0 (disabled) | Time before warning about un-returned connections |
| `connectionTestQuery` | null | Test query (null = use JDBC4 `isValid()`) |
| `connectionInitSql` | null | SQL to execute on new connections |
| `isAutoCommit` | true | Default autoCommit for connections |
| `isReadOnly` | false | Default readOnly for connections |
| `initializationFailTimeout` | 1 | How long to wait for initial connection |

### 8.2 Config Sealing

After the pool starts, `HikariConfig` is "sealed" — most setters throw `IllegalStateException`. Only properties exposed via `HikariConfigMXBean` can be changed at runtime (connectionTimeout, idleTimeout, leakDetectionThreshold, maxLifetime, minimumIdle, maximumPoolSize, etc.).

---

## 9. Metrics

### 9.1 IMetricsTracker Interface

```java
void recordConnectionCreatedMillis(long connectionCreatedMillis);
void recordConnectionAcquiredNanos(long elapsedAcquiredNanos);
void recordConnectionUsageMillis(long elapsedBorrowedMillis);
void recordConnectionTimeout();
```

### 9.2 Delegate Pattern

`PoolBase.IMetricsTrackerDelegate` wraps `IMetricsTracker`. When metrics are disabled, `NopMetricsTrackerDelegate` (empty methods) is used — the JIT can inline and eliminate the entire call chain.

### 9.3 PoolStats

Abstract class with lazy-loading (cached for a configurable period). Provides:
- `totalConnections`, `idleConnections`, `activeConnections`
- `pendingThreads`, `maxConnections`, `minConnections`

---

## 10. Error Handling

### 10.1 Broken Connection Detection

`ProxyConnection.checkException()` inspects each `SQLException`:
- SQL States starting with "08" (connection exception class) → evict
- Known SQL states: "0A000", "57P01", "57P02", "57P03", "01002", "JZ0C0", "JZ0C1" → evict
- Known error codes: 500150, 2399, 1105 → evict
- `SQLExceptionOverride` interface allows custom adjudication (DO_NOT_EVICT, CONTINUE_EVICT, MUST_EVICT)

### 10.2 Connection Failure Tracking

`PoolBase` tracks:
- `lastConnectionFailure` (AtomicReference<Throwable>) — last exception from connection creation
- `connectionFailureTimestamp` (AtomicLong) — when failures started (for empty-pool warnings)

### 10.3 Pool Initialization

`checkFailFast()`: If `initializationFailTimeout >= 0`, tries to create one connection at startup. If it fails within the timeout, throws `PoolInitializationException`. If `initializationFailTimeout < 0`, pool starts even without a valid connection.
