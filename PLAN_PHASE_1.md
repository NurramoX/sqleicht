# Phase 1: FFM Bindings (SQLite C API)

Detailed implementation plan for the Panama FFM layer that replaces JNI.

Reference: sqlite-jdbc's `NativeDB.java` is available via `crocodocs read-files sqlite-jdbc src/main/java/org/sqlite/core/NativeDB.java`
for further inspection of how they use each function.

---

## 1. Project Setup

- [x] **1.1** Choose base package name: `io.sqleicht` (replacing the placeholder `org.example`)
- [x] **1.2** Update `build.gradle.kts`:
  - Remove `commons-math3` and `guava` dependencies (not needed)
  - Add JVM args for FFM access: `--enable-native-access=ALL-UNNAMED` (or module name)
  - Configure test task with the same JVM args
- [x] **1.3** Create package structure under `lib/src/main/java/io/sqleicht/`:
  - `io.sqleicht.ffi` — raw FFM bindings (`native` is a Java reserved keyword)
  - `io.sqleicht.core` — handle wrappers and error mapping

---

## 2. SQLite Constants

- [x] **2.1** `SQLiteResultCode` — enum mapping SQLite result codes
  - Primary codes: `SQLITE_OK` (0), `SQLITE_ERROR` (1), `SQLITE_BUSY` (5), `SQLITE_LOCKED` (6),
    `SQLITE_NOMEM` (7), `SQLITE_READONLY` (8), `SQLITE_INTERRUPT` (9), `SQLITE_IOERR` (10),
    `SQLITE_CORRUPT` (11), `SQLITE_FULL` (13), `SQLITE_CANTOPEN` (14), `SQLITE_CONSTRAINT` (19),
    `SQLITE_MISUSE` (21), `SQLITE_RANGE` (25), `SQLITE_NOTADB` (26),
    `SQLITE_ROW` (100), `SQLITE_DONE` (101)
  - Include extended error codes for the most common ones (constraint subtypes, busy subtypes, ioerr subtypes)
  - Helper: `static SQLiteResultCode fromCode(int code)`
  - Helper: `boolean isError()` — true if not OK/ROW/DONE

- [x] **2.2** `SQLiteOpenFlag` — constants for `sqlite3_open_v2` flags
  - `SQLITE_OPEN_READONLY` (0x01), `SQLITE_OPEN_READWRITE` (0x02), `SQLITE_OPEN_CREATE` (0x04)
  - `SQLITE_OPEN_URI` (0x40), `SQLITE_OPEN_MEMORY` (0x80)
  - `SQLITE_OPEN_NOMUTEX` (0x8000), `SQLITE_OPEN_FULLMUTEX` (0x10000)
  - `SQLITE_OPEN_SHAREDCACHE` (0x20000), `SQLITE_OPEN_PRIVATECACHE` (0x40000)
  - We default to `READWRITE | CREATE | NOMUTEX | PRIVATECACHE` (single-threaded access per connection, managed by our pool)

- [x] **2.3** `SQLiteColumnType` — constants for `sqlite3_column_type` return values
  - `SQLITE_INTEGER` (1), `SQLITE_FLOAT` (2), `SQLITE_TEXT` (3), `SQLITE_BLOB` (4), `SQLITE_NULL` (5)

---

## 3. Native Library Loading

- [x] **3.1** `SQLiteLibrary` — singleton responsible for loading `sqlite3` and exposing its `SymbolLookup`
  - Use `SymbolLookup.libraryLookup("sqlite3", Arena.global())` (Unix) / `"winsqlite3"` (Windows)
  - Fall back to `"libsqlite3.so"` / `"libsqlite3.dylib"` based on OS detection
  - Optionally accept an explicit library path via system property `sqleicht.sqlite.library.path`
  - Store the `SymbolLookup` instance; all function handle lookups go through it
  - Fail fast with a clear error message if the library is not found

---

## 4. FFM Function Bindings — `SQLiteNative`

Central class that defines `MethodHandle`s for each SQLite C function via the Panama `Linker`.

All handles are `private static final` fields, initialized once at class load time.
Each has a corresponding `static` Java method that invokes the handle and translates arguments/results.

### 4.1 Connection Management

- [x] **4.1.1** `sqlite3_open_v2(const char *filename, sqlite3 **ppDb, int flags, const char *zVfs) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)`
  - Java wrapper: `static MemorySegment open(Arena arena, String filename, int flags)` — allocates pointer-to-pointer for `ppDb`, calls native, returns the `sqlite3*` pointer, throws on error
- [x] **4.1.2** `sqlite3_close_v2(sqlite3*) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS)`
  - Java wrapper: `static void close(MemorySegment db)` — throws on error

### 4.2 Error Reporting

- [x] **4.2.1** `sqlite3_errmsg(sqlite3*) → const char*`
  - FFM signature: `FunctionDescriptor.of(ADDRESS, ADDRESS)`
  - Java wrapper: `static String errmsg(MemorySegment db)` — reads C string from returned pointer
- [x] **4.2.2** `sqlite3_errcode(sqlite3*) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS)`
  - Java wrapper: `static int errcode(MemorySegment db)`
- [x] **4.2.3** `sqlite3_extended_errcode(sqlite3*) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS)`
  - Java wrapper: `static int extendedErrcode(MemorySegment db)`

### 4.3 Statement Lifecycle

- [x] **4.3.1** `sqlite3_prepare_v2(sqlite3*, const char *zSql, int nByte, sqlite3_stmt **ppStmt, const char **pzTail) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)`
  - Java wrapper: `static MemorySegment prepare(Arena arena, MemorySegment db, String sql)` — allocates pointer-to-pointer for `ppStmt`, passes `-1` for `nByte` (null-terminated), ignores `pzTail` (pass `NULL`), returns `sqlite3_stmt*`
- [x] **4.3.2** `sqlite3_step(sqlite3_stmt*) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS)`
  - Java wrapper: `static int step(MemorySegment stmt)` — returns raw result code (SQLITE_ROW, SQLITE_DONE, or error)
- [x] **4.3.3** `sqlite3_reset(sqlite3_stmt*) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS)`
  - Java wrapper: `static void reset(MemorySegment stmt)`
- [x] **4.3.4** `sqlite3_finalize(sqlite3_stmt*) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS)`
  - Java wrapper: `static void finalizeStmt(MemorySegment stmt)` (renamed to avoid `Object.finalize` clash)
- [x] **4.3.5** `sqlite3_clear_bindings(sqlite3_stmt*) → int`
  - FFM signature: `FunctionDescriptor.of(JAVA_INT, ADDRESS)`
  - Java wrapper: `static void clearBindings(MemorySegment stmt)`

### 4.4 Binding Parameters

All bind functions: `(sqlite3_stmt*, int index, ...) → int`. Index is 1-based.

- [x] **4.4.1** `sqlite3_bind_int(stmt, int, int) → int`
- [x] **4.4.2** `sqlite3_bind_int64(stmt, int, long) → int`
- [x] **4.4.3** `sqlite3_bind_double(stmt, int, double) → int`
- [x] **4.4.4** `sqlite3_bind_text(stmt, int, const char*, int nByte, destructor) → int`
  - Pass `-1` for nByte (null-terminated), `SQLITE_TRANSIENT` (cast of -1) for destructor so SQLite copies the string
  - Java wrapper: `static void bindText(Arena arena, MemorySegment stmt, int index, String value)` — allocates C string via arena, calls native
- [x] **4.4.5** `sqlite3_bind_blob(stmt, int, const void*, int nByte, destructor) → int`
  - Pass `SQLITE_TRANSIENT` for destructor
  - Java wrapper: `static void bindBlob(Arena arena, MemorySegment stmt, int index, byte[] value)`
- [x] **4.4.6** `sqlite3_bind_null(stmt, int) → int`
- [x] **4.4.7** `sqlite3_bind_parameter_count(stmt) → int`

### 4.5 Reading Columns

All column functions: `(sqlite3_stmt*, int iCol) → value`. Index is 0-based.

- [x] **4.5.1** `sqlite3_column_int(stmt, int) → int`
- [x] **4.5.2** `sqlite3_column_int64(stmt, int) → long`
- [x] **4.5.3** `sqlite3_column_double(stmt, int) → double`
- [x] **4.5.4** `sqlite3_column_text(stmt, int) → const unsigned char*`
  - Java wrapper: `static String columnText(MemorySegment stmt, int col)` — reads null-terminated UTF-8 string
- [x] **4.5.5** `sqlite3_column_blob(stmt, int) → const void*`
  - Also needs `sqlite3_column_bytes(stmt, int) → int` to know the blob size
  - Java wrapper: `static byte[] columnBlob(MemorySegment stmt, int col)` — reads into Java byte array
- [x] **4.5.6** `sqlite3_column_bytes(stmt, int) → int`
- [x] **4.5.7** `sqlite3_column_type(stmt, int) → int`
  - Returns one of the SQLITE_INTEGER/FLOAT/TEXT/BLOB/NULL constants
- [x] **4.5.8** `sqlite3_column_count(stmt) → int`
- [x] **4.5.9** `sqlite3_column_name(stmt, int) → const char*`
  - Java wrapper: `static String columnName(MemorySegment stmt, int col)`

### 4.6 Miscellaneous

- [x] **4.6.1** `sqlite3_busy_timeout(sqlite3*, int ms) → int`
- [x] **4.6.2** `sqlite3_changes(sqlite3*) → int` (rows changed by last INSERT/UPDATE/DELETE)
- [x] **4.6.3** `sqlite3_last_insert_rowid(sqlite3*) → long`
- [x] **4.6.4** `sqlite3_exec(sqlite3*, const char *sql, callback, void*, char **errmsg) → int`
  - Simple wrapper for non-parameterized SQL (pragmas, DDL). Pass `NULL` for callback, arg, errmsg.
  - Java wrapper: `static void exec(Arena arena, MemorySegment db, String sql)` — for fire-and-forget SQL like `PRAGMA journal_mode=WAL`
- [x] **4.6.5** `sqlite3_libversion() → const char*`
  - Java wrapper: `static String libversion()` — for diagnostics

---

## 5. Handle Wrapper Types

Thin value types wrapping `MemorySegment` pointers. Provide type safety so you can't accidentally
pass a statement pointer where a connection pointer is expected.

These handles are **only ever accessed from their owning platform thread** (see §9 — dedicated
thread model). No synchronization needed. Confined arenas are safe.

- [x] **5.1** `SQLiteConnectionHandle`
  - Wraps `MemorySegment` for a `sqlite3*` pointer
  - Implements `AutoCloseable` → calls `SQLiteNative.close()`
  - Owns a **confined** `Arena` — safe because the handle is permanently pinned to one platform thread
  - `close()` also closes the arena, freeing all native memory (connection pointer + any statement pointers allocated in it)
  - `boolean isClosed()` method (check if pointer is `NULL`)
  - Package-private constructor: only the pool/connection layer should create these

- [x] **5.2** `SQLiteStatementHandle`
  - Wraps `MemorySegment` for a `sqlite3_stmt*` pointer
  - Allocated in the parent connection's confined arena (same thread, same arena)
  - Implements `AutoCloseable` → calls `SQLiteNative.finalizeStmt()`
  - `boolean isClosed()` method
  - Package-private constructor

---

## 6. String and Memory Utilities

- [x] **6.1** Helper for Java `String` → null-terminated UTF-8 `MemorySegment`
  - `static MemorySegment allocate(Arena arena, String s)` — uses `arena.allocateFrom(s)` (Java 22+ FFM API)
- [x] **6.2** Helper for C `const char*` → Java `String`
  - `static String read(MemorySegment ptr)` — uses `ptr.reinterpret(Long.MAX_VALUE).getString(0)`
  - Handle `NULL` pointer → return `null`
- [x] **6.3** Arena strategy decision (see §9 for rationale):
  - Connection lifetime: `Arena.ofConfined()` — each connection is permanently pinned to one dedicated platform thread, so confined is safe and cheaper than shared
  - Temporary strings: allocated in the connection's confined arena or a method-scoped confined arena — freed after the FFM call (`SQLITE_TRANSIENT` tells SQLite to copy immediately)
  - Library loading: `Arena.global()` — lives forever

---

## 7. Exception Mapping

- [x] **7.1** `SQLeichtException` — base checked exception
  - Fields: `int errorCode`, `int extendedErrorCode`, `String message`
  - Factory: `static SQLeichtException fromConnection(MemorySegment db, int resultCode)` — reads `errmsg()` and `extended_errcode()` from the connection
  - Factory: `static SQLeichtException fromCode(int resultCode, String context)` — for cases where no connection is available (e.g., open failed)

---

## 8. Smoke Test

- [x] **8.1** Integration test that exercises the full FFM layer end-to-end:
  - Open an in-memory database (`:memory:`)
  - Execute `CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT, value REAL)`
  - Prepare + bind + step an `INSERT`
  - Prepare + step + read columns from a `SELECT`
  - Verify column types, values, and column names
  - Close statement and connection
  - Blob round-trip test
  - Statement reset/rebind test
  - Last insert rowid test
  - Busy timeout test

- [x] **8.2** Error path test:
  - Attempt to open a non-existent read-only database → verify `SQLeichtException` with `SQLITE_CANTOPEN`
  - Prepare invalid SQL → verify `SQLeichtException` with `SQLITE_ERROR` and meaningful message
  - Bind out-of-range index → verify `SQLeichtException` with `SQLITE_RANGE`

---

## 9. Implementation Notes

### Dedicated Platform Thread Model

**FFM calls pin the carrier thread.** JEP 491 (Java 24) solved `synchronized` pinning, but
foreign function calls via Panama still pin. If virtual threads called FFM directly, every
`sqlite3_step()`, `sqlite3_prepare_v2()`, etc. would hold a carrier thread for the duration.

**Our solution: dedicated platform threads for all FFM work.**

The architecture separates concerns into two layers:

```
┌─────────────────────────────────────────────────┐
│  Virtual Threads (application code)             │
│  - Submit work items to their connection's      │
│    dedicated thread                             │
│  - Await results (virtual thread yields,        │
│    carrier is free for other virtual threads)   │
└──────────────────┬──────────────────────────────┘
                   │ submit + await
                   ▼
┌─────────────────────────────────────────────────┐
│  Dedicated Platform Threads (1 per connection)  │
│  - Own the sqlite3* handle and its Arena        │
│  - Execute ALL FFM calls for that connection    │
│  - Single-threaded: no locking needed           │
│  - No carrier pinning concern (platform thread) │
└─────────────────────────────────────────────────┘
```

**Why this is the right design:**
- **Zero carrier pinning** — virtual threads never touch FFM. They submit work and park
  (yielding their carrier) while waiting for results.
- **Confined arenas everywhere** — each connection + its statements live on exactly one thread.
  `Arena.ofConfined()` is safe and cheaper than `Arena.ofShared()` (no reference counting).
- **No SQLite mutex needed** — `SQLITE_OPEN_NOMUTEX` is correct because each connection is
  only ever accessed from its dedicated thread. No concurrent access, no data races.
- **`sqlite3_busy_timeout` can be any value** — a 5-second busy wait blocks a dedicated platform
  thread, not a carrier. Virtual threads waiting for results are parked, not pinning anything.
- **Pool size is decoupled from carrier count** — the pool manages dedicated platform threads,
  not carriers. You can have 20 pooled connections without needing 20 carrier threads.

**Phase 1 impact:**
- Phase 1 builds the raw FFM bindings only — static methods that call native functions.
- The dedicated thread model is implemented in Phase 2 (connection wrapper) and Phase 3 (pool).
- Phase 1 code has no threading concerns: `SQLiteNative` methods are stateless and can be
  called from any thread. The threading discipline is enforced by higher layers.

### Arena Lifetime Strategy

**`Arena.ofConfined()` is used for connection lifetimes.** Each connection is permanently pinned
to one dedicated platform thread, so only that thread ever accesses the arena's segments. This
avoids the overhead of `Arena.ofShared()` (which needs atomic reference counting).

```
Connection open (on dedicated platform thread):
  Arena connArena = Arena.ofConfined();  // safe — this thread owns the arena forever
  MemorySegment db = SQLiteNative.open(connArena, path, flags);

Statement prepare (same dedicated thread):
  // The stmt pointer is allocated in the connection's confined arena
  MemorySegment sqlStr = connArena.allocateFrom(sql);
  MemorySegment stmt = SQLiteNative.prepare(connArena, db, sqlStr);
  // sqlStr can stay in the arena (small, freed when connection closes)
  // OR use SQLITE_TRANSIENT and a separate temp allocation strategy

Bind text (same dedicated thread):
  // SQLITE_TRANSIENT tells SQLite to copy immediately
  // The C string can be allocated in the connection's arena (simple)
  // or in a method-scoped arena if memory pressure matters

Connection close (same dedicated thread):
  SQLiteNative.close(db);
  connArena.close();  // frees ALL native memory at once — connection, statements, strings
```

**Arena summary:**

| Scope | Arena Type | Reason |
|-------|-----------|--------|
| Library lookup | `Arena.global()` | Lives forever, loaded once |
| Connection lifetime (`sqlite3*`) | `Arena.ofConfined()` | Connection is pinned to one dedicated platform thread |
| Statement lifetime (`sqlite3_stmt*`) | Connection's confined arena | Statement is tied to its connection and thread |
| Temporary strings (SQL, bind values) | Connection's confined arena | Same thread; `SQLITE_TRANSIENT` tells SQLite to copy immediately |

### SQLITE_TRANSIENT

`SQLITE_TRANSIENT` is defined as `((sqlite3_destructor_type)-1)` in C — it's a special sentinel
pointer value. In FFM, represent it as `MemorySegment.ofAddress(-1)`.

### Thread Safety

All `SQLiteNative` methods are **stateless** static methods — no synchronization needed at this layer.
Thread safety is the responsibility of the pool and connection wrappers (Phase 2+3).
The open flag `SQLITE_OPEN_NOMUTEX` disables SQLite's internal mutexes, which is correct since
we guarantee single-threaded access per connection via the pool.

### Method Naming

Where SQLite C names clash with Java reserved words or `Object` methods:
- `sqlite3_finalize` → `finalizeStmt` (avoids `Object.finalize()`)

---

## 10. Files Created

```
lib/src/main/java/io/sqleicht/
├── ffi/
│   ├── SQLiteLibrary.java       (§3 — library loading)
│   └── SQLiteNative.java        (§4 — all FFM function bindings)
├── core/
│   ├── SQLiteResultCode.java    (§2.1 — result code enum)
│   ├── SQLiteOpenFlag.java      (§2.2 — open flag constants)
│   ├── SQLiteColumnType.java    (§2.3 — column type constants)
│   ├── SQLiteConnectionHandle.java  (§5.1 — connection pointer wrapper)
│   ├── SQLiteStatementHandle.java   (§5.2 — statement pointer wrapper)
│   └── SQLeichtException.java      (§7.1 — exception)
└── util/
    └── Utf8.java                (§6 — string/memory helpers)

lib/src/test/java/io/sqleicht/
└── ffi/
    └── SQLiteNativeTest.java    (§8 — smoke + error tests)
```
