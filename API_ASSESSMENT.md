# Core Infrastructure Assessment

## 1. Virtual Thread Support & Thread Pinning

### What you got right — the Java-side locking is flawless

- Zero `synchronized` blocks in the entire codebase. Every lock is a `ReentrantLock` or `Semaphore` — both VT-friendly, both allow carrier thread unmounting while waiting.
- `ThreadLocal<ConnectionSlot> heldSlot` for reentrancy: always cleaned up in `finally` (`heldSlot.remove()`), no leak risk even with millions of VTs passing through.
- Housekeeping runs on `Thread.ofVirtual()` — sleeps cleanly unmount the VT.
- `LockSupport.parkNanos(1_000)` in `acquireSlot()` — VT-friendly, yields the carrier.
- The `Semaphore` acts as a natural concurrency limiter: at most N virtual threads can enter the FFM zone simultaneously. This is actually *the* correct design for FFM + virtual threads.

### The unavoidable reality — FFM downcalls pin carrier threads

Every `invokeExact()` on a downcall `MethodHandle` pins the carrier thread for the duration of the native call. This is a JVM-level constraint, not a sqleicht bug. But let's trace the impact:

```
VT calls db.query(...)
  → semaphore.tryAcquire()      // VT-friendly, unmounts if waiting
  → slot.lock().tryLock()        // VT-friendly, unmounts if waiting
  → prepare (FFM call)           // PINS carrier ~1-10µs
  → bindInt (FFM call)           // PINS carrier ~0.5µs
  → step (FFM call)              // PINS carrier ~1µs-10ms per row
  → columnLong (FFM call)        // PINS carrier ~0.5µs
  → ... repeat per row ...
  → reset (FFM call)             // PINS carrier ~0.5µs
  → slot.lock().unlock()         // unpins, carrier free
  → semaphore.release()
```

With default `threadCount=2`, at most 2 carrier threads are pinned simultaneously. Since the default carrier pool is `Runtime.availableProcessors()`, this is fine — you're using 2 out of (typically) 8-16 carriers.

**Where it could matter:** A full table scan stepping through 100K rows pins a carrier for the entire duration (each `step()` is a separate pin, but they happen back-to-back on the same carrier with no chance to unmount in between). This is inherent to how SQLite works — it's a synchronous, non-yielding native library. Your pool design correctly limits the blast radius.

**Verdict:** The pinning situation is as good as it can be given that SQLite is a synchronous native library. The Semaphore-based backpressure is exactly the right pattern — it ensures carrier thread pinning is bounded to `threadCount`, not to the number of virtual threads trying to access the database.

### Improvement opportunity — `Linker.Option.critical()`

For trivial, guaranteed-fast native calls, the `critical` linker option skips the Java-to-native thread state transition. This avoids the safepoint poll overhead and (importantly for VTs) reduces the pinning cost. Candidates:

| Good candidates for `critical(false)` | Why |
|---|---|
| `columnInt`, `columnLong`, `columnDouble` | Pure register reads, ~10ns |
| `columnType`, `columnCount`, `columnBytes` | Pure metadata reads |
| `columnName` | Pointer read from compiled statement |
| `changes64`, `lastInsertRowid` | Single field read from db handle |
| `errcode`, `extendedErrcode` | Single field read |
| `bindParameterCount`, `stmtStatus` | Metadata access |
| `libversion` | Returns static pointer |

Not candidates: `step` (may do I/O), `prepare` (may parse complex SQL), `open_v2` / `close_v2` (lifecycle), `exec` (may run multiple statements).

The change would look like:

```java
private static MethodHandle downcall(String name, FunctionDescriptor desc, Linker.Option... opts) {
    MemorySegment symbol = LIB.find(name).orElseThrow(...);
    return LINKER.downcallHandle(symbol, desc, opts);
}

// Then:
private static final MethodHandle COLUMN_INT =
    downcall("sqlite3_column_int",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
        Linker.Option.critical(false));
```

This is a meaningful optimization for read-heavy workloads — a query reading 10 columns per row across 10K rows makes 100K column accessor calls, and each one currently pays the full transition overhead.

---

## 2. FFM Usage Quality

### MethodHandle setup — textbook

- All 30+ handles are `static final`, created once at class-load time. The JIT can treat these as constants and potentially inline the invoke sites.
- `invokeExact()` used consistently — no boxing overhead from `invoke()`.
- Single `downcall()` helper keeps the boilerplate centralized.

### SQLITE_STATIC safety — correct and well-reasoned

The bind path is:

```
Arena.ofConfined() opened
  → bindParams allocates UTF-8/blob into this arena
  → bind_text/blob with SQLITE_STATIC (no copy — SQLite trusts the pointer)
  → step() reads the bound values
  → arena closes (memory freed)
```

SQLITE_STATIC is safe here because the arena outlives the `step()` call. The confined arena closes *after* the step loop completes in `update()`, `query()`, and `forEach()`. In `batch()`, each iteration has its own confined arena with the correct bind-step-close sequence. No dangling pointer risk.

### Arena strategy — exactly right for the ownership model

| Arena type | Where | Why |
|---|---|---|
| `Arena.global()` | Library loading | Library lives for JVM lifetime |
| `Arena.ofShared()` | Per-connection (`SQLiteConnectionHandle`) | Housekeeping thread may close connections created by other threads. Shared allows cross-thread close. |
| `Arena.ofConfined()` | Per-task bind allocations | Zero synchronization overhead. Safe because bind+step always happens on the same thread. |
| `Arena.ofConfined()` | `SQLeichtLiveStatement.bindArena` | Same reasoning. Recycled on `reset()`. |

The choice of `ofShared()` for connections is the one that matters most. If you'd used `ofConfined()`, the idle-timeout close in `housekeepingLoop()` would crash — it runs on a different virtual thread than the one that created the arena. `ofShared()` is the correct (and only) choice here.

### `reinterpret()` usage — two patterns, both correct

1. **`ptr.reinterpret(Long.MAX_VALUE).getString(0)`** in `Utf8.read()` — standard C string reading pattern. Every call site passes a pointer from SQLite that is guaranteed null-terminated (`sqlite3_errmsg`, `sqlite3_column_text`, `sqlite3_column_name`). Safe.

2. **`ptr.reinterpret(size)`** in `columnTextSegment()` / `columnBlobSegment()` — bounded reinterpretation using `sqlite3_column_bytes()`. SQLite guarantees `column_bytes` returns the correct size after `column_text`/`column_blob` without recomputing. Safe and efficient.

### Connection close ordering — correct

```java
// SQLiteConnectionHandle.close()
db = null;                    // 1. Mark closed (volatile write, visible to other threads)
stmtCache.close();            // 2. Finalize all cached statements (needs valid arena)
SQLiteNative.close(toClose);  // 3. Close database (needs valid arena for the db segment)
arena.close();                // 4. Free all Java-side memory (last)
```

Statements finalized before database closed (required by SQLite). Arena closed last (required by FFM — all segments must be used before arena closes). Correct.

### `sqlite3_exec()` for PRAGMAs — pragmatic

`ConnectionSlot.openConnection()` runs 8+ PRAGMAs via `SQLiteNative.exec()`. Each `exec()` call allocates a temporary UTF-8 string in the connection arena. These allocations accumulate (arena can't free individual allocations — it's a bump allocator). For 8 strings of ~30 bytes each, that's ~240 bytes per connection open. Negligible.

### Real concern — `SQLiteNative.prepare()` allocates into the connection arena

```java
// StatementCache.acquire() → SQLiteNative.prepare(arena, db, sql)
public static MemorySegment prepare(Arena arena, MemorySegment db, String sql, int prepFlags) {
    MemorySegment sqlStr = Utf8.allocate(arena, sql);   // allocated in connection arena
    MemorySegment ppStmt = arena.allocate(ADDRESS);      // allocated in connection arena
    ...
    return ppStmt.get(ADDRESS, 0);
}
```

Every `prepare()` call allocates `sqlStr` (the UTF-8 SQL) and `ppStmt` (the out-pointer) into the connection's shared arena. These are never freed until the connection closes. For a long-lived connection executing many distinct SQL strings, this is a slow memory accumulation.

Typical impact: if you execute 1000 distinct queries averaging 100 bytes each, that's ~100KB per connection. Not catastrophic, but worth knowing. The statement cache bounds this somewhat (only `statementCacheSize` statements are alive at once), but the *arena allocations* for evicted statements still linger.

**Fix:** Use a short-lived `Arena.ofConfined()` for the `sqlStr` and `ppStmt` allocations in `prepare()`, since they're only needed during the `sqlite3_prepare_v3()` call itself. The returned `MemorySegment` (the compiled statement) is owned by SQLite internally — it doesn't reference the arena allocation:

```java
public static MemorySegment prepare(Arena connectionArena, MemorySegment db, String sql, int prepFlags) {
    try (var tempArena = Arena.ofConfined()) {
        MemorySegment sqlStr = Utf8.allocate(tempArena, sql);
        MemorySegment ppStmt = tempArena.allocate(ADDRESS);
        int rc = (int) PREPARE_V3.invokeExact(db, sqlStr, -1, prepFlags, ppStmt, MemorySegment.NULL);
        if (rc != 0) throw SQLeichtException.fromConnection(db, rc);
        return ppStmt.get(ADDRESS, 0);
    }
}
```

This eliminates the slow arena leak entirely.

---

## 3. Modern Java Usage

### What you're using well

| Feature | Where | Notes |
|---|---|---|
| Pattern matching for switch | `bindParams()` | Clean type dispatch, handles 14 types including temporal |
| Virtual threads | Housekeeping thread | `Thread.ofVirtual()` |
| FFM (Panama) | Entire native layer | `Linker`, `Arena`, `MemorySegment`, `MethodHandle` |
| `var` type inference | Throughout | Try-with-resources, test code |
| `ReentrantLock` over `synchronized` | Pool infrastructure | VT-friendly by design |
| `Thread.onSpinWait()` | `acquireSlot()` | Hint to the CPU for spin-wait optimization |
| Functional interfaces | `TaskFunction`, `RowConsumer`, `TransactionFunction`, `TransactionConsumer` | Clean lambda ergonomics |
| `LinkedHashMap` access-order | `StatementCache` | LRU with no external dependency |

### What you could adopt

**`ScopedValue` instead of `ThreadLocal` for `heldSlot`:**

`ScopedValue` (standard in Java 25) is purpose-built for the exact pattern you have — a value that exists for the duration of a scoped operation:

```java
// Current:
heldSlot.set(slot);
try {
    return task.apply(conn);
} finally {
    heldSlot.remove();
}

// With ScopedValue:
private static final ScopedValue<ConnectionSlot> HELD_SLOT = ScopedValue.newInstance();

// In submit():
return ScopedValue.where(HELD_SLOT, slot).call(() -> task.apply(conn));

// Reentrancy check:
if (HELD_SLOT.isBound()) { ... }
```

Benefits: `ScopedValue` is cheaper than `ThreadLocal` for virtual threads (no per-VT hash map entry), automatically scoped (no risk of forgetting `remove()`), and immutable within the scope. It's semantically more accurate for what you're doing.

---

## Overall Verdict

| Area | Grade | Notes |
|---|---|---|
| Virtual thread safety | **A** | No `synchronized`, all VT-friendly primitives, correct concurrency limiting |
| FFM correctness | **A** | Arena lifecycles correct, SQLITE_STATIC safe, close ordering right |
| FFM performance | **B+** | Missing `Linker.Option.critical()` for leaf calls; arena leak in `prepare()` |
| Modern Java | **B+** | Good use of pattern matching, VTs, FFM. Could adopt `ScopedValue`. |

### The two actionable items

1. **`Linker.Option.critical(false)`** on column accessors and trivial metadata reads — measurable win for read-heavy workloads.
2. **Short-lived arena in `SQLiteNative.prepare()`** — the `sqlStr` and `ppStmt` allocations currently leak into the connection arena. Use a temporary confined arena to eliminate the slow accumulation.

Everything else is solid. The core is well-engineered for the constraints you're operating under.
