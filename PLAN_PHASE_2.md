# Phase 2: Connection Executor + Client API

Merges the original Phase 2 (Core API) and Phase 3 (Connection Pool) into a single design.
The **ConnectionExecutor is the pool** — it owns a fixed set of platform threads, each with
its own SQLite connection, and virtual threads just submit tasks.

On top of the executor sits the **`SQLeicht` client** — the user-facing API that hides the
executor entirely. Users just write SQL. No lambdas, no `submit()`, no connection management.

### Design Principles — Data-Oriented, Not JDBC-Oriented

This is **not a JDBC wrapper**. JDBC's design has fundamental problems we avoid entirely:

| JDBC Problem | sqleicht Approach |
|---|---|
| **Zero-copy impossible** — `getString()` always allocates a `java.lang.String`, `getBytes()` always allocates `byte[]`. Every result row triggers heap allocations. | **Arena-backed results** — row data lives in a `MemorySegment` arena. `getSegment()` returns a zero-copy view. `getText()` available for convenience but optional. |
| **DatabaseMetaData is heavy** — column names require secondary queries or expensive string parsing in most drivers. | **Column names are free** — `sqlite3_column_name()` is a direct pointer into SQLite's prepared statement structure. Read once during batch creation, zero overhead. |
| **Cursor-oriented** — `ResultSet.next()` moves one row at a time, holding a connection open for the entire iteration. | **Batch by default** — entire result set materialized into an arena in one atomic task. No cursor, no connection holding, no leak. |
| **String-oriented** — all values pass through Java String/Object wrappers. | **Segment-oriented** — text and blob data exposed as `MemorySegment` views. No charset conversion or heap allocation unless the user explicitly requests it. |

```java
var db = SQLeicht.create(":memory:");

db.execute("CREATE TABLE sensors (id INTEGER, name TEXT, data BLOB, temp REAL)");
db.update("INSERT INTO sensors VALUES (?, ?, ?, ?)", 1, "probe-A", sensorBytes, 23.5);

try (var rows = db.query("SELECT * FROM sensors WHERE temp > ?", 20.0)) {
    for (var row : rows) {
        int id = row.getInt(0);                    // direct read, no boxing
        MemorySegment name = row.getSegment(1);    // zero-copy view into arena
        MemorySegment data = row.getSegment(2);    // zero-copy blob, no byte[] alloc
        String nameStr = row.getText("name");      // convenience: reads from segment
    }
}   // arena freed — all segments invalidated at once

db.close();
```

### HikariCP Takeaways — What We Keep

| HikariCP Concept | sqleicht Equivalent | Why |
|---|---|---|
| CAS state machine (NOT_IN_USE / IN_USE / RESERVED / REMOVED) | `ConnectionSlot` states via `AtomicIntegerFieldUpdater` | Lock-free slot management |
| Semaphore for acquisition limiting | `Semaphore(threadCount)` gates `submit()` | Virtual-thread-friendly backpressure |
| Max lifetime with random variance | Scheduled reconnect per slot | Prevent thundering herd, WAL/memory hygiene |
| Housekeeping (30s period) | Virtual-thread-based scheduled task | Eviction, health checks |
| Config sealing after start | `sealed` volatile flag | Prevent mutation of running pool |
| Timestamp tracking (lastAccessed) | On each slot after task completes | For idle/lifetime decisions |
| Soft eviction flag (mark now, close on return) | `evict` volatile on `ConnectionSlot` | Non-disruptive rotation |

### HikariCP Takeaways — What We Drop

| HikariCP Concept | Why Not |
|---|---|
| ThreadLocal fast path | Virtual threads can be millions — unbounded memory waste |
| SynchronousQueue handoff | Not needed — work queues replace borrow/return entirely |
| CopyOnWriteArrayList shared scan | No shared list to scan — each thread has its own queue |
| ConcurrentBag | Replaced by per-thread work queues + Semaphore |
| ProxyConnection / Javassist proxies | We own the API, no JDBC proxying needed |
| Keepalive validation | SQLite is in-process — no network to drop |
| Suspend/resume | YAGNI for embedded database |
| Leak detection | No borrow/return = no leak. Tasks complete or fail. |
| Clock anomaly detection | SQLite is local — no NTP-sensitive network timeouts |

---

## 1. SQLeicht Client — The User-Facing API

The entry point. Users create an instance with a path and optional config, then call SQL methods
directly. No connection objects, no executor lambdas — just SQL in, results out.

Under the hood, every method submits a self-contained task to the `ConnectionExecutor`.
The entire prepare → bind → step → read → finalize cycle happens atomically on a platform thread
in a single task submission.

### 1.1 Creation and Lifecycle

- [x] **1.1.1** `SQLeicht` — static factory methods
  - `SQLeicht.create(String path)` — opens with default config
  - `SQLeicht.create(String path, SQLeichtConfig config)` — opens with custom config
  - Creates and owns a `ConnectionExecutor` internally
  - Implements `AutoCloseable` — `close()` shuts down the executor

### 1.2 DDL / Pragmas

- [x] **1.2.1** `void execute(String sql)`
  - For DDL, pragmas, non-parameterized statements
  - Submits a single task: `conn.execute(sql)`

### 1.3 Parameterized Updates

- [x] **1.3.1** `int update(String sql, Object... params)`
  - For INSERT, UPDATE, DELETE with parameters
  - Submits one atomic task that: prepares → binds all params → steps → returns `changes()`
  - Parameter types resolved at runtime: `Integer`, `Long`, `Double`, `String`, `byte[]`, `null`
  - Returns the number of changed rows

### 1.4 Parameterized Queries

- [x] **1.4.1** `SQLeichtRows query(String sql, Object... params)`
  - For SELECT statements
  - Submits one atomic task on the platform thread that:
    1. Creates a **shared `Arena`** for the result data (shared because it's created on the
       platform thread but consumed on the calling virtual thread)
    2. Prepares the statement, binds params
    3. Steps through all rows — for each row, copies column data into the result arena using
       `columnTextSegment()` / `columnBlobSegment()` → `MemorySegment.copy()` (single memcpy)
    4. Reads column names once (cheap — `sqlite3_column_name` is a pointer into the statement)
    5. Finalizes the statement
    6. Returns `SQLeichtRows` which owns the arena
  - `SQLeichtRows` implements `AutoCloseable` — `close()` frees the arena and invalidates
    all segments at once

### 1.5 Prepared Statement Builder

- [x] **1.5.1** `SQLeichtStatement prepare(String sql)`
  - Returns a **builder** — bindings accumulate in memory on the virtual thread side (no FFM calls)
  - `bind(int index, ...)` methods store bindings in a list
  - `executeUpdate()` submits one atomic task: native prepare → bind all → step → finalize
  - `query()` submits one atomic task: native prepare → bind all → step all rows → finalize
    → returns arena-backed `SQLeichtRows`
  - The builder is **reusable** — call `reset()`, re-bind, execute again
  - The builder is **not** a live native statement — it's a recipe that runs atomically

### 1.6 Convenience

- [x] **1.6.1** `long lastInsertRowid()` — submits task to read `sqlite3_last_insert_rowid`
- [x] **1.6.2** `int changes()` — submits task to read `sqlite3_changes`

### 1.7 Advanced Escape Hatch

- [x] **1.7.1** `<T> T submit(TaskFunction<T> task)` — raw executor access
  - For streaming large result sets, multi-statement transactions, or anything the high-level
    API doesn't cover
  - `TaskFunction<T>`: `T apply(SQLeichtConnection conn) throws SQLeichtException`
  - The lambda runs entirely on the platform thread with direct access to the connection
  - Inside the lambda, the user gets true zero-copy access — `columnTextSegment()` returns
    a view directly into SQLite's internal buffer (valid until next step)

### 1.8 Metrics

- [x] **1.8.1** Delegated from executor
  - `int activeCount()`, `int idleCount()`, `int pendingCount()`, `int threadCount()`

---

## 2. Arena-Backed Batch Results — `SQLeichtRows` / `SQLeichtRow`

Results are materialized into a **shared `Arena`** during the single atomic task. The arena
owns all row data — text and blob values live as `MemorySegment` views into arena-allocated
buffers. No Java heap allocation for row data. No `String` or `byte[]` created unless the
user explicitly asks for it.

### Copy Strategy

```
During query execution (on platform thread):

  For each row from sqlite3_step():
    For each column:
      ┌─ INTEGER/FLOAT → store as primitive (long/double) in a Java array
      ├─ TEXT →
      │    src = columnTextSegment(stmt, col)  ← zero-copy view into SQLite buffer
      │    dst = resultArena.allocate(src.byteSize())
      │    MemorySegment.copy(src, 0, dst, 0, src.byteSize())  ← single memcpy
      │    store dst segment reference
      ├─ BLOB →
      │    src = columnBlobSegment(stmt, col)  ← zero-copy view into SQLite buffer
      │    dst = resultArena.allocate(src.byteSize())
      │    MemorySegment.copy(src, 0, dst, 0, src.byteSize())  ← single memcpy
      │    store dst segment reference
      └─ NULL → store null marker

  sqlite3_step() returns DONE → finalize statement → return batch
```

**Copy count comparison:**

| Operation | JDBC | sqleicht |
|---|---|---|
| Read text column | SQLite buf → JNI buf → byte[] → String (3+ copies) | SQLite buf → arena buf (1 memcpy) |
| Read blob column | SQLite buf → JNI buf → byte[] (2+ copies) | SQLite buf → arena buf (1 memcpy) |
| Access by user | String/byte[] on heap (GC pressure) | MemorySegment view (zero-copy, no GC) |
| Cleanup | GC collects each String/byte[] individually | `arena.close()` frees everything at once |

### 2.1 `SQLeichtRows`

- [x] **2.1.1** Core structure
  - Implements `Iterable<SQLeichtRow>`, `AutoCloseable`
  - Owns a **shared `Arena`** — all text/blob data lives in it
  - `close()` closes the arena, invalidating all segments
  - Column metadata: `String[] columnNames`, `int[] columnTypes` (read once, cheap)

- [x] **2.1.2** Accessors
  - `int size()` — number of rows
  - `boolean isEmpty()`
  - `SQLeichtRow get(int index)` — access by row index
  - `List<String> columnNames()`
  - `int columnCount()`

### 2.2 `SQLeichtRow`

- [x] **2.2.1** Segment access (zero-copy, primary API)
  - `MemorySegment getSegment(int col)` — raw segment view into arena buffer
    - For TEXT: the UTF-8 bytes without null terminator
    - For BLOB: the raw blob bytes
    - For NULL: returns `MemorySegment.NULL`
    - For INTEGER/FLOAT: not applicable (use `getInt`/`getDouble`)
  - `MemorySegment getSegment(String name)` — same, by column name

- [x] **2.2.2** Typed access by index (0-based)
  - `int getInt(int col)` — reads stored primitive
  - `long getLong(int col)` — reads stored primitive
  - `double getDouble(int col)` — reads stored primitive
  - `String getText(int col)` — convenience: reads UTF-8 string from segment (`segment.getString(0)`)
  - `byte[] getBlob(int col)` — convenience: copies segment to `byte[]` (`segment.toArray()`)
  - `boolean isNull(int col)` — checks null marker

- [x] **2.2.3** Typed access by name
  - `int getInt(String name)` (and all other types including `getSegment`)
  - Column name → index resolved via the parent `SQLeichtRows`'s name list (HashMap, built once)

---

## 3. ConnectionExecutor — The Pool (Internal)

The internal pool. Owns N platform threads (configurable, default 2), each permanently
bound to one SQLite connection. The `SQLeicht` client delegates to this; users don't
interact with it directly (except via the `submit()` escape hatch).

### 3.1 Core Structure

- [x] **3.1.1** `ConnectionExecutor` — constructor
  - `ConnectionExecutor(String path, SQLeichtConfig config)` — opens the pool
  - Creates `N` platform threads (`sqleicht-ffi-0`, `sqleicht-ffi-1`, ...)
  - Each thread owns: a `LinkedBlockingQueue`, a `ConnectionSlot`, a confined `Arena`
  - Opens all SQLite connections eagerly at construction (on each thread's first task)
  - Implements `AutoCloseable`

- [x] **3.1.2** `ConnectionSlot` — per-thread connection state (inner class or separate class)
  - Wraps: `SQLiteConnectionHandle`, `Arena`, timestamps
  - State field via `AtomicIntegerFieldUpdater`: NOT_IN_USE (0), IN_USE (1), RESERVED (-2), REMOVED (-1)
  - `long lastAccessed` — nanoTime of last task completion
  - `long createdAt` — nanoTime of connection open
  - `volatile boolean evict` — soft eviction flag

- [x] **3.1.3** Semaphore-based backpressure
  - `Semaphore(threadCount, fair=true)` — limits concurrent in-flight tasks
  - `submit()` acquires a permit (virtual thread parks — carrier freed)
  - Permit released after task completes
  - Timeout: `connectionTimeout` from config (default 30s)

### 3.2 Task Submission (internal)

- [x] **3.2.1** `<T> T submit(TaskFunction<T> task)` — internal API used by `SQLeicht`
  - Acquires semaphore permit
  - Picks next available thread (round-robin via `AtomicInteger`)
  - Wraps task in `FutureTask`, enqueues on chosen thread's queue
  - Parks until result ready
  - Unwraps checked exceptions cleanly
  - Releases permit in `finally`

- [x] **3.2.2** Exception propagation
  - `SQLeichtException` propagates through `FutureTask` unwrapping
  - `RuntimeException` propagates directly
  - Semaphore timeout → `SQLeichtException("Connection timeout")`

### 3.3 Configuration — `SQLeichtConfig`

- [x] **3.3.1** `SQLeichtConfig` — builder-pattern config
  - `threadCount` — number of platform threads / connections (default: 2)
  - `maxLifetime` — max connection age before rotation (default: 30 minutes)
  - `busyTimeout` — SQLite busy timeout in ms (default: 5000)
  - `journalMode` — WAL by default
  - `connectionInitSql` — optional SQL to run on each new connection (e.g., `PRAGMA foreign_keys=ON`)
  - `connectionTimeout` — max time to wait for a semaphore permit (default: 30s)

- [x] **3.3.2** Config validation (inspired by HikariCP)
  - `maxLifetime >= 30_000ms`
  - `connectionTimeout >= 250ms`
  - `threadCount >= 1`

- [x] **3.3.3** Config sealing
  - `volatile boolean sealed` — set `true` after `ConnectionExecutor` construction
  - Setters throw `IllegalStateException` if sealed

### 3.4 Housekeeping

- [x] **3.4.1** Housekeeping virtual thread
  - Runs every 30 seconds (configurable)
  - Simple virtual thread + `Thread.sleep()` loop

- [x] **3.4.2** Max lifetime rotation
  - Housekeeping checks: if `now - slot.createdAt > maxLifetime`, sets `slot.evict = true`
  - Random variance: actual lifetime = `maxLifetime - random(0, maxLifetime / 4)`
  - Platform thread checks evict flag between tasks and reconnects if marked

- [x] **3.4.3** Connection health check
  - On rotation: verify new connection via `SELECT 1`
  - If open fails: log, retry with backoff

### 3.5 Shutdown

- [x] **3.5.1** `close()` — graceful shutdown
  - Set pool state to SHUTDOWN (reject new `submit()` calls)
  - Drain work queues (let in-flight tasks complete)
  - Close all SQLite connections on their owning threads
  - Close all arenas, join all threads
  - Cancel housekeeping

### 3.6 Metrics

- [x] **3.6.1** Simple metrics
  - `int activeCount()` — slots IN_USE
  - `int idleCount()` — slots NOT_IN_USE
  - `int pendingCount()` — `semaphore.getQueueLength()`
  - `int threadCount()` — total platform threads

---

## 4. Internal Connection — `SQLeichtConnection` (Platform Thread Only)

A handle passed into `TaskFunction` lambdas (the `submit()` escape hatch). Runs directly
on the platform thread — no executor indirection. Not user-constructable.

This is where **true zero-copy** lives — `columnTextSegment()` returns a view directly
into SQLite's internal buffer. No memcpy at all. Valid until the next `step()`.

- [x] **4.1** `SQLeichtConnection` — core structure
  - Wraps `SQLiteConnectionHandle` + `Arena`
  - All methods call FFM directly (already on the correct thread)

- [x] **4.2** Execute raw SQL
  - `void execute(String sql)`

- [x] **4.3** Prepare statements (live native statements)
  - `SQLeichtLiveStatement prepare(String sql)` — returns a live native statement handle
  - `SQLeichtLiveStatement` wraps `SQLiteStatementHandle` with direct FFM calls
  - Used for streaming large result sets row-by-row without materialization

- [x] **4.4** Convenience
  - `long lastInsertRowid()`
  - `int changes()`

---

## 5. Phase 1 Additions

These zero-copy segment methods were added to `SQLiteNative` to support the batch reader:

- [x] **5.1** `MemorySegment columnTextSegment(stmt, col)` — returns a view into SQLite's
  internal text buffer. Valid until next step/reset/finalize.
- [x] **5.2** `MemorySegment columnBlobSegment(stmt, col)` — returns a view into SQLite's
  internal blob buffer. Valid until next step/reset/finalize.

The existing `columnText()` → `String` and `columnBlob()` → `byte[]` methods remain for
convenience and for the escape hatch's `SQLeichtLiveStatement`.

---

## 6. Tests

### Client API Tests

- [x] **6.1** `SQLeichtTest` — tests for the high-level client
  - `execute()` — CREATE TABLE, PRAGMA
  - `update()` — INSERT with params, verify return count
  - `update()` — UPDATE with params, verify return count
  - `query()` — SELECT with params, verify `SQLeichtRows` contents
  - `query()` — empty result set
  - `query()` — NULL values
  - `prepare().bind().executeUpdate()` — builder pattern
  - `prepare().bind().query()` — builder pattern for SELECT
  - `prepare().reset().bind().executeUpdate()` — reuse builder
  - Auto-type resolution: int, long, double, String, byte[], null params
  - `lastInsertRowid()` and `changes()`
  - Column access by name in `SQLeichtRow`
  - Close and verify operations throw after close

### Zero-Copy Tests

- [x] **6.2** `ZeroCopyTest` — verify the arena-backed segment model
  - Query rows, access `getSegment()`, verify contents match expected UTF-8 bytes
  - Verify `getSegment()` and `getText()` return the same data
  - Verify blob `getSegment()` vs `getBlob()` equivalence
  - Verify `getSegment()` does NOT allocate on the Java heap (no String/byte[] created)
  - Verify `rows.close()` invalidates all segments (access after close fails)
  - Verify the arena is shared (created on platform thread, accessed on virtual thread)

### Executor Tests

- [x] **6.3** `ConnectionExecutorTest` — unit tests for the internal pool
  - Submit a task, verify it runs on a `sqleicht-ffi-*` platform thread
  - Verify exception propagation (checked and unchecked)
  - Verify configurable thread count (1, 2, 4)
  - Verify shutdown stops all threads and rejects new submissions
  - Verify semaphore backpressure: submit `threadCount + 1` slow tasks, last one blocks

### Thread Partitioning Tests

- [x] **6.4** `ThreadPartitionTest` — verify the platform/virtual thread boundary
  - Record `Thread.currentThread()` inside submitted tasks
  - Assert all recorded threads are platform threads (not virtual)
  - Assert all thread names match `sqleicht-ffi-*`
  - Assert distinct thread count equals configured thread count
  - Verify calling (virtual) thread ≠ executing (platform) thread
  - Use the high-level `SQLeicht` API — verify partition holds through client layer

### Virtual Thread Tests

- [x] **6.5** `VirtualThreadTest` — verify virtual threads never run FFM
  - Spawn 100 virtual threads, each calling `db.query()`/`db.update()` on the same `SQLeicht`
  - Collect threads that executed FFM work
  - Assert: only platform threads, max `threadCount` distinct

### Load Test

- [x] **6.6** `LoadTest` — 1000 concurrent tasks
  - Create `SQLeicht.create(":memory:")` with default config (2 threads)
  - Pre-create a table
  - Spawn **1000 virtual threads**, each doing:
    - `db.update("INSERT INTO t VALUES (?)", uniqueId)`
    - `try (var rows = db.query("SELECT * FROM t WHERE id = ?", uniqueId)) { ... }`
    - Verify the row came back correctly
  - Assert: all 1000 complete without error
  - Assert: tasks ran concurrently (max concurrent virtual threads >> 2)
  - Assert: FFM work handled by exactly 2 platform threads

### Housekeeping Tests

- [x] **6.7** `HousekeepingTest` — max lifetime rotation
  - Create executor with `maxLifetime = 2 seconds`
  - Submit a task, note connection identity
  - Wait for rotation
  - Submit another task, verify different connection
  - Verify no errors during rotation

### Robustness Tests

- [x] **6.8** Nested submit deadlock prevention
  - **Fix:** detect re-entrant submit (task running on `sqleicht-ffi-*` calling submit again)
    and execute inline instead of queuing, or throw immediately
  - **Test:** `threadCount=1`, inside `db.submit()` call `db.update()` — must not deadlock
  - **Test:** `threadCount=2`, both threads doing nested submits simultaneously — must not deadlock
  - **Test:** verify the detection works through the client API (not just raw submit)

- [x] **6.9** Dead worker thread recovery
  - **Fix:** if `openConnection()` fails, fail all queued FutureTasks with an error instead of
    hanging; if a worker thread dies mid-operation, detect and propagate errors
  - **Test:** force a connection open failure (e.g. invalid path), verify submit throws
    immediately instead of hanging
  - **Test:** verify remaining healthy threads continue serving tasks

- [x] **6.10** NOMUTEX + SHAREDCACHE safety
  - **Fix:** when using shared cache (`:memory:` with N>1), drop `NOMUTEX` — let SQLite handle
    its own internal locking for the shared cache structures
  - **Test:** 100 concurrent writers on `:memory:` with 2 threads, verify no corruption
  - **Test:** verify NOMUTEX is still used for file-based databases (no shared cache)

- [x] **6.11** Arena leak safety for unclosed SQLeichtRows
  - **Fix:** add `Cleaner` action on `SQLeichtRows` that closes the arena if the user forgets
  - **Test:** create rows, drop reference without closing, trigger GC, verify arena is cleaned up
  - **Test:** verify explicit close still works and doesn't double-close

- [x] **6.12** Shutdown race prevention
  - **Fix:** after setting SHUTDOWN, drain semaphore permits to reject in-flight acquires;
    fail any newly queued FutureTasks with IllegalStateException
  - **Test:** start shutdown while tasks are in-flight, verify in-flight tasks complete,
    new tasks are rejected, no hangs

---

## 7. Implementation Notes

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Application Code (any thread — typically virtual)           │
│                                                              │
│  var db = SQLeicht.create(":memory:");                       │
│                                                              │
│  db.execute("CREATE TABLE ...");                             │
│  db.update("INSERT INTO t VALUES (?, ?)", 1, "hello");       │
│                                                              │
│  try (var rows = db.query("SELECT * FROM t")) {              │
│      for (var row : rows) {                                  │
│          MemorySegment s = row.getSegment(1); // zero-copy   │
│          int id = row.getInt(0);              // primitive   │
│      }                                                       │
│  } // arena.close() — all segments freed at once             │
│                                                              │
│  // Advanced — true zero-copy streaming:                     │
│  db.submit(conn -> {                                         │
│      try (var stmt = conn.prepare("SELECT ...")) {           │
│          // columnTextSegment() → direct view into SQLite    │
│      }                                                       │
│  });                                                         │
└──────────────┬───────────────────────────────────────────────┘
               │
               │  Each call → one atomic task on a platform thread
               │  Results → arena-backed MemorySegments (single memcpy)
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│  ConnectionExecutor (internal, hidden from user)             │
│                                                              │
│  Semaphore(2)  ← backpressure                               │
│                                                              │
│  ┌─────────────────────┐  ┌─────────────────────┐           │
│  │ sqleicht-ffi-0      │  │ sqleicht-ffi-1      │           │
│  │ [ConnectionSlot]    │  │ [ConnectionSlot]    │           │
│  │ sqlite3* + Arena    │  │ sqlite3* + Arena    │           │
│  │ [work queue]        │  │ [work queue]        │           │
│  └─────────────────────┘  └─────────────────────┘           │
│                                                              │
│  Housekeeping (virtual thread, 30s interval)                 │
└──────────────────────────────────────────────────────────────┘
```

### Two Levels of Zero-Copy

**Level 1 — Batch results (default API):** One memcpy per value.
SQLite buffer → result arena (memcpy) → user gets `MemorySegment` view (zero-copy).
This is the best you can do with batch semantics since SQLite's internal buffer is
reused on each `step()`.

**Level 2 — Streaming (escape hatch):** True zero-copy.
`db.submit(conn -> { ... })` runs on the platform thread. `columnTextSegment()` returns
a view directly into SQLite's internal buffer. No memcpy at all. Valid only until the
next `step()`.

### Arena Lifecycle for Results

```
Platform thread (during query task):
  │
  ├─ var resultArena = Arena.ofShared();   // shared — will cross thread boundary
  │
  ├─ sqlite3_prepare_v2(...)
  ├─ sqlite3_bind_*(...)
  │
  ├─ LOOP: sqlite3_step() → ROW
  │   └─ For each column:
  │       ├─ INT/FLOAT → store as primitive (in Java array, no arena needed)
  │       ├─ TEXT → src = columnTextSegment(stmt, col)     // view into SQLite buf
  │       │         dst = resultArena.allocate(src.byteSize())
  │       │         MemorySegment.copy(src, 0, dst, 0, ...)  // single memcpy
  │       ├─ BLOB → same as TEXT
  │       └─ NULL → store null marker
  │
  ├─ sqlite3_finalize(...)
  │
  └─ return new SQLeichtRows(resultArena, rows, columnNames, columnTypes)
         │
         └─► Ownership of resultArena transfers to SQLeichtRows
             The calling virtual thread now owns it
             User calls rows.close() → resultArena.close()
             All MemorySegments invalidated at once (deterministic, no GC)
```

**Why `Arena.ofShared()` for results (not confined):**
The result arena is created on the platform thread but consumed by the calling virtual thread.
Confined arenas are thread-bound — `close()` from a different thread throws
`WrongThreadException`. Shared arenas support cross-thread access and closure.

**Connection arenas remain confined** — they never leave their platform thread.

### Why Statement Builders (Not Live Statements)

The `SQLeicht` client's `prepare()` returns a **builder**, not a live native `sqlite3_stmt*`:

```
Traditional (live statement):
  Virtual thread                    Platform thread
  ─────────────                    ───────────────
  stmt = prepare("...")  ───────►  sqlite3_prepare_v2  ← holds connection!
  stmt.bind(1, 42)       ───────►  sqlite3_bind_int    ← still holding!
  stmt.execute()          ───────►  sqlite3_step        ← still holding!
  stmt.close()            ───────►  sqlite3_finalize    ← released

  Problem: 4 round-trips. Connection held throughout. Leak if close() forgotten.
```

```
sqleicht (builder):
  Virtual thread                    Platform thread
  ─────────────                    ───────────────
  stmt = prepare("...")            (builder created in memory — no FFM)
  stmt.bind(1, 42)                 (binding stored in List — no FFM)
  stmt.executeUpdate()   ───────►  prepare → bind → step → finalize (ONE atomic task)

  One round-trip. No connection holding. No leak.
```

### Connection Rotation (Max Lifetime)

```
Platform thread ffi-0, between tasks:
  │
  ├─ Dequeue next task
  ├─ Check: is slot.evict set?
  │   ├─ YES → close old connection, open fresh one (same thread)
  │   └─ NO  → continue
  ├─ slot.state = IN_USE
  ├─ Run task
  ├─ slot.lastAccessed = now
  └─ slot.state = NOT_IN_USE
```

### Error Handling

- `SQLeicht` methods on a closed instance → `IllegalStateException`
- FFM errors → `SQLeichtException` propagated cleanly through `FutureTask`
- Semaphore timeout → `SQLeichtException("Connection timeout — no slot available within Nms")`
- Connection rotation failure → log, retry with backoff
- `SQLeichtRows` access after `close()` → segment access throws `IllegalStateException`
  (arena already closed)

---

## 8. Files to Create

```
lib/src/main/java/io/sqleicht/
├── SQLeicht.java                   (§1 — user-facing client)
├── SQLeichtConfig.java             (§3.3 — configuration)
├── SQLeichtRows.java               (§2.1 — arena-backed batch result)
├── SQLeichtRow.java                (§2.2 — single row with segment access)
├── SQLeichtStatement.java          (§1.5 — statement builder)
├── core/
│   ├── ConnectionExecutor.java     (§3 — internal pool)
│   ├── ConnectionSlot.java         (§3.1.2 — per-thread state)
│   ├── SQLeichtConnection.java     (§4 — internal connection for escape hatch)
│   ├── SQLeichtLiveStatement.java  (§4.3 — live native statement for streaming)
│   └── SQLeichtLiveResultSet.java  (§4.3 — live cursor for streaming)

lib/src/test/java/io/sqleicht/
├── SQLeichtTest.java               (§6.1 — client API tests)
├── ZeroCopyTest.java               (§6.2 — arena/segment verification)
├── core/
│   ├── ConnectionExecutorTest.java (§6.3)
│   ├── ThreadPartitionTest.java    (§6.4)
│   ├── VirtualThreadTest.java      (§6.5)
│   └── HousekeepingTest.java       (§6.7)
└── load/
    └── LoadTest.java               (§6.6)
```
