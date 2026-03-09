# sqleicht — Implementation Plan

## Key Design Constraints

- **Zero `synchronized`** — `ReentrantLock`, `Semaphore`, `StampedLock`, and `j.u.c` atomics only
- **Panama FFM only** — no JNI, no `native` keyword
- **Java 25** — preview features are fair game

## Reference Material

HikariCP architecture has been documented in `docs/` for implementation inspiration:

- `docs/hikaricp-architecture.md` — component overview, connection lifecycle, thread model, config surface
- `docs/hikaricp-concurrentbag-deep-dive.md` — ConcurrentBag borrow/requite algorithms, CAS state machine, ThreadLocal caching
- `docs/hikaricp-poolentry-and-lifecycle.md` — PoolEntry internals, AtomicIntegerFieldUpdater, scheduled eviction tasks
- `docs/hikaricp-config-and-housekeeping.md` — config defaults/validation/sealing, HouseKeeper, idle eviction, leak detection
- `docs/hikaricp-virtual-thread-analysis.md` — audit of every `synchronized` site, virtual-thread-safe alternatives

The HikariCP source is tracked locally via crocodocs (`crocodocs read-files hikaricp <path>`).
Use it for further inspection when implementing pool internals.

## Phase 1: FFM Bindings (SQLite C API) ✅

Detailed plan: `PLAN_PHASE_1.md`

- [x] `SQLiteNative` — 30 FFM downcall bindings via Panama `Linker`
- [x] `SQLiteLibrary` — runtime library loading (`sqlite3` / `winsqlite3`)
- [x] `SQLiteConnectionHandle`, `SQLiteStatementHandle` — type-safe `MemorySegment` wrappers
- [x] `SQLiteResultCode`, `SQLiteOpenFlag`, `SQLiteColumnType` — constants
- [x] `SQLeichtException` — error code mapping
- [x] `Utf8` — string/memory helpers
- [x] 10 smoke + error path tests — all passing

## Phase 2: Connection Executor + Client API

Detailed plan: `PLAN_PHASE_2.md`

Merges the original Phase 2 (Core API) and Phase 3 (Connection Pool) into a single design.
The executor IS the pool — no separate pool layer needed. Data-oriented, not JDBC-oriented.

### Design: Zero-Copy, Arena-Backed, Batch-First
- [x] Results live in a shared `Arena` as `MemorySegment` views — no `String`/`byte[]` heap allocation
- [x] Single memcpy per value (SQLite buffer → result arena), then zero-copy access by user
- [x] Column metadata is free — `sqlite3_column_name` is a pointer read, not a secondary query
- [x] Escape hatch (`submit()`) provides true zero-copy: direct views into SQLite's internal buffers

### ConnectionExecutor (the pool)
- [x] Fixed pool of N platform threads (configurable, default 2), each owning one SQLite connection
- [x] `ConnectionSlot` — per-thread state with CAS state machine (HikariCP-inspired)
- [x] `Semaphore` backpressure — limits concurrent tasks to thread count
- [x] Housekeeping — max lifetime rotation with random variance, health checks
- [x] `SQLeichtConfig` — builder-pattern configuration with validation and sealing

### SQLeicht Client (user-facing API)
- [x] `SQLeicht` — the entry point users interact with
  - `execute(sql)` — DDL, pragmas
  - `query(sql, params...)` — parameterized SELECT, returns arena-backed `SQLeichtRows`
  - `update(sql, params...)` — parameterized INSERT/UPDATE/DELETE, returns change count
  - `prepare(sql)` — returns a statement builder (bindings accumulate, execute atomically)
  - `submit(conn -> ...)` — escape hatch for streaming, transactions, true zero-copy
- [x] `SQLeichtRows` / `SQLeichtRow` — arena-backed result batch with `getSegment()` zero-copy access
- [x] `SQLeichtStatement` — builder that accumulates bindings, executes atomically
- [x] Pool metrics: `activeCount()`, `idleCount()`, `pendingCount()`

### Tests
- [x] Client API tests + zero-copy verification
- [x] Thread partition tests (FFM only on `sqleicht-ffi-*` threads, never virtual)
- [x] Load test (1000 concurrent virtual threads, 2 platform threads)
- [x] Housekeeping test (max lifetime rotation)
