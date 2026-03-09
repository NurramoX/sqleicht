# Code Review Findings

## Bugs

### 1. `rotate()` failure leaves slot with no connection (ConnectionExecutor:86-91)

When `slot.rotate()` fails, the exception is silently caught. But `rotate()` calls `closeConnection()` *before* `openConnection()`. If `openConnection()` throws, the old connection is already gone. The comment says "continue with existing connection" — but there isn't one. The next line `slot.connection()` returns `null`, producing an NPE or segfault.

```java
// ConnectionSlot.rotate()
public void rotate() throws SQLeichtException {
    closeConnection();   // ← closes first
    openConnection();    // ← if this throws, connection is null
    evict = false;
}

// ConnectionExecutor.submit() — caller
if (slot.evict) {
    try {
        slot.rotate();
    } catch (SQLeichtException e) {
        // rotation failed — continue with existing connection  ← LIE: it's gone
    }
}
```

**Fix**: Either reverse the order (open new, then close old) or propagate the error.

### 2. `step()` errors silently swallowed in `executeUpdate` (SQLeicht:169)

`SQLiteNative.step(stmt)` can return error codes (CONSTRAINT, BUSY, etc.), but `executeUpdate()` discards the return value. A failed INSERT due to a UNIQUE constraint would silently report 0 changes instead of throwing.

```java
SQLiteNative.step(stmt);  // return value ignored
return SQLiteNative.changes(conn.db());
```

The same issue exists in `SQLeichtTransaction.update()` at line 31 and `SQLeichtTransaction.batch()` at line 135.

### 3. `columnTypes` frozen from first row only (SQLeicht:199-207, SQLeichtTransaction:58-66)

In `executeQuery()`, the `columnTypes` array is populated from the first row and then shared (by reference) across all rows. SQLite is dynamically typed — different rows in the same column can have different types. Rows after the first will have a `columnTypes` array that doesn't match their actual stored values.

```java
if (columnTypes == null) {            // only enters on first row
    columnTypes = new int[colCount];
    for (int i = 0; i < colCount; i++) {
        columnTypes[i] = SQLiteNative.columnType(stmt, i);
    }
}
// ...
rows.add(new SQLeichtRow(values, columnTypes, nameIndex));  // same array ref for all rows
```

### 4. `SQLeichtLiveStatement.bindText/bindBlob` leaks arena memory (SQLeichtLiveStatement:31,36)

These methods allocate into the *connection's* long-lived arena. Every bind call permanently grows the arena until the connection closes. The main API correctly uses task-scoped `Arena.ofConfined()`, but the `LiveStatement` escape hatch doesn't, making it a silent memory leak under repeated use.

```java
public SQLeichtLiveStatement bindText(int index, String value) throws SQLeichtException {
    SQLiteNative.bindText(connection.arena(), handle.stmt(), index, value);  // ← connection arena
    return this;
}
```

---

## Code Smells

### 5. Massive duplication: query/forEach/update logic copied 3x

The column-name resolution, step loop, and row construction logic is duplicated almost verbatim between:
- `SQLeicht.executeQuery()` and `SQLeichtTransaction.query()`
- `SQLeicht.forEach()` and `SQLeichtTransaction.forEach()`
- `SQLeicht.executeUpdate()` and `SQLeichtTransaction.update()`

A shared internal helper (or having `SQLeicht`'s methods delegate through the transaction path) would eliminate ~100 lines of duplicated code.

### 6. `SQLeichtConnection` exposes raw FFI handles publicly (SQLeichtConnection:31,35,39)

`db()`, `stmtCache()`, and `arena()` are `public` methods returning raw `MemorySegment` and internal types. Since `SQLeichtConnection` is the type users receive via `submit(TaskFunction)`, they can directly manipulate native pointers — risking crashes, double-frees, or use-after-free. These should be package-private.

### 7. PRAGMA values not validated — SQL injection surface (ConnectionSlot:36-53)

Config string values (`journalMode`, `synchronous`, `autoVacuum`, `tempStore`, `connectionInitSql`) are interpolated directly into SQL via string concatenation:

```java
SQLiteNative.exec(arena, db, "PRAGMA journal_mode=" + journalMode);
```

While these come from the builder API, there's no validation that they're legitimate PRAGMA values. A caller passing `"WAL; DROP TABLE users; --"` as `journalMode` would execute arbitrary SQL.

### 8. `SQLiteConnectionHandle.db` not volatile — visibility issue (SQLiteConnectionHandle:9)

The `db` field is set to `null` in `close()` and checked in `isClosed()`. Since `close()` can be called from the housekeeping thread (via `slot.closeConnection()`) while `isClosed()` is checked from pool threads, the `null` write may not be visible across threads without `volatile`.

### 9. `acquireSlot()` busy-spins under contention (ConnectionExecutor:171-180)

The method spins in a tight loop with only `Thread.yield()`. On virtual threads pinned to a small platform thread pool, this can waste the entire platform thread budget spinning instead of doing useful work.

### 10. `SQLeichtRows` stores unused fields (SQLeichtRows:10-11)

`columnTypes` and `nameIndex` are stored as fields but never read — they're only used inside `SQLeichtRow`. Dead fields that inflate object size.

### 11. `Math.random()` in housekeeping thread (ConnectionExecutor:197)

`Math.random()` uses a shared synchronized `Random` instance. Should use `ThreadLocalRandom.current().nextLong(...)` for better performance and to avoid contention.

### 12. `SQLeichtException` is not `final` (SQLeichtException:6)

The class is `public` but not `final`, allowing external subclassing. This is likely unintentional since all construction goes through static factories.

### 13. `SQLeichtRows.close()` is a no-op (SQLeichtRows:50-52)

Implements `AutoCloseable` but `close()` does nothing. Users will write try-with-resources blocks that provide no value. Either remove `AutoCloseable` or document it as forward-compatible.

### 14. `fromCode()` linear scan on every exception (SQLiteResultCode:72-79)

`SQLiteResultCode.fromCode()` iterates all enum values on every call. A static `Map<Integer, SQLiteResultCode>` lookup would be O(1) instead of O(n).

---

## Test Coverage Gaps

### Critical: No test proves `update()` throws on constraint violations

This is the missing test that would expose Bug #2. A simple UNIQUE constraint violation via `update()` is silently swallowed — `step()` returns an error code but it's never checked, and the statement cache's `release()` catches the error from `reset()` and silently finalizes the statement. This test would fail today:

```java
db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
db.update("INSERT INTO t VALUES (?)", 1);
assertThrows(SQLeichtException.class, () -> db.update("INSERT INTO t VALUES (?)", 1));
```

The existing `batchRollsBackOnError` test only passes by accident — `batch()` catches the error through `reset()` propagation, not through `step()` checking.

### Critical: No test for `rotate()` failure path

No test verifies what happens when a connection rotation fails (e.g., disk full, permissions revoked). This would expose Bug #1 — the slot is left with a null connection after `closeConnection()` runs but `openConnection()` throws.

### Critical: `lastInsertRowid()` / `changes()` return wrong values across slots

`lastInsertRowid()` and `changes()` are connection-scoped SQLite state, but the public API calls `executor.submit()` which may route to *any* connection slot. After `db.update(...)` on slot 0, calling `db.lastInsertRowid()` may hit slot 1 and return stale/wrong data. No test verifies cross-slot correctness. The `lastInsertRowid` test in `SQLeichtTest` sidesteps this by using `submit()` to ensure same-connection access (the comment on line 174-175 even acknowledges the problem).

### No test for primitive getter on NULL column

`getInt()`, `getLong()`, `getDouble()` do unboxing casts (`(int)(long) values[col]`). When the column is NULL, `values[col]` is `null` and this throws `NullPointerException`. No test covers this, and there's no documentation that callers must use `isNull()` first. A `getIntOrDefault(col, default)` or similar would be safer.

### No test for `Float`, `Short`, `Byte` parameter binding

`bindParams()` handles `Integer`, `Long`, `Double` but not `Float`, `Short`, or `Byte`. Passing `3.14f` or `(short) 42` hits the `default` branch and throws `"Unsupported parameter type"`. This is surprising since these are common Java types that have obvious SQLite mappings.

### No test for nested `transaction()` inside `transaction()`

What happens when user code calls `db.transaction(tx -> { db.transaction(inner -> { ... }); })` ? The inner `transaction()` calls `conn.execute("BEGIN")` while already inside a transaction, which SQLite rejects with `"cannot start a transaction within a transaction"`. No test verifies this behavior or provides a useful error message.

### No test for `forEach` when the consumer throws

If the `RowConsumer` throws mid-iteration, does the statement get properly released back to the cache? The code structure suggests it does (the `finally` block runs), but there's no test confirming it. More importantly, there's no test that the *connection slot* is properly released.

### No test for unknown column name

`SQLeichtRow.resolveColumn(name)` throws `IllegalArgumentException` for unknown names, but there's no test for this path.

### No test for out-of-bounds column index

`row.getInt(99)` would throw `ArrayIndexOutOfBoundsException` from the JVM, not a descriptive library error. No test covers this, and no bounds checking exists.

### No test for empty string or empty blob round-trip

Empty strings (`""`) and empty blobs (`new byte[0]`) are edge cases in SQLite's type system. An empty blob could be confused with NULL in some contexts. No test verifies these round-trip correctly.

### No test for `SQLeichtStatement` with mismatched parameter count

Binding 3 parameters for a query with 2 placeholders, or binding index 1 and 3 but skipping 2, produces undefined behavior (unbound params default to NULL in SQLite). No test verifies the library handles this gracefully.

### No test for config validation edge cases

`SQLeichtConfig.validate()` has several validation rules but only `invalidPathFailsFast` in `RobustnessTest` touches error paths. No tests for: `threadCount(0)`, `maxLifetimeMs(100)`, `connectionTimeoutMs(0)`, `pageSize(7)`, `statementCacheSize(-1)`, etc.

### No test for double-close safety

Calling `db.close()` twice, or `close()` followed by other operations, has no test coverage. The pool drains the semaphore on close, but a second close would drain again — the interaction is untested.

### No test for large result sets with `query()` (OOM risk)

`query()` materializes all rows into a `List<SQLeichtRow>` in memory. There's a `forEachStreamsWithoutAccumulating` test that proves `forEach` doesn't OOM on 100K rows, but no corresponding test that `query()` on 100K rows *does* OOM (or at least consumes significant memory). Users need to understand when to use `forEach` vs `query`.

### Critical: No test for transaction failure mid-way (SQLite-level errors)

The existing `transactionRollsBackOnException` test only throws a *Java* exception from user code. There's no test where a *SQLite operation inside the transaction* fails — e.g., a CHECK constraint, foreign key violation, or inserting into a non-existent table. These are the real-world failure modes. A proper test would be:

```java
db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val INTEGER CHECK(val > 0))");
try {
    db.transaction(tx -> {
        tx.update("INSERT INTO t VALUES (?, ?)", 1, 10);   // succeeds
        tx.update("INSERT INTO t VALUES (?, ?)", 2, -5);   // CHECK violation
        return null;
    });
} catch (SQLeichtException e) { /* expected */ }
// row 1 should be rolled back too
assertEquals(0, db.query("SELECT COUNT(*) FROM t").get(0).getInt(0));
```

This is especially important because of Bug #2 (`step()` errors swallowed) — a transaction might *partially commit* if intermediate errors are silently ignored and only the final COMMIT succeeds.

### Critical: No test for concurrent transactions on separate slots

With `threadCount(2)`, two transactions can run simultaneously on different connections. There's no test for this. Key scenarios:

- **Write-write contention**: Two transactions inserting into the same table concurrently. With WAL mode, one should succeed and the other should either succeed or get SQLITE_BUSY. No test verifies this works (or fails gracefully with a useful error).
- **Serialization correctness**: Two transactions that each read-then-write should not produce lost updates. E.g., both read `counter=5`, both write `counter=6` — the final value should be 7, not 6.
- **Rollback isolation**: If transaction A inserts rows and then rolls back, a concurrent transaction B should never see A's uncommitted rows (assuming the default isolation level).

```java
// Example: concurrent transaction contention
try (var db = SQLeicht.create(dbPath, new SQLeichtConfig().threadCount(2))) {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, counter INTEGER)");
    db.update("INSERT INTO t VALUES (1, 0)");

    CountDownLatch bothInTx = new CountDownLatch(2);
    AtomicInteger errors = new AtomicInteger();

    for (int i = 0; i < 2; i++) {
        Thread.ofVirtual().start(() -> {
            try {
                db.transaction(tx -> {
                    var rows = tx.query("SELECT counter FROM t WHERE id = 1");
                    int val = rows.get(0).getInt(0);
                    bothInTx.countDown();
                    bothInTx.await(); // both read before either writes
                    tx.update("UPDATE t SET counter = ? WHERE id = 1", val + 1);
                    return null;
                });
            } catch (Exception e) { errors.incrementAndGet(); }
        });
    }
    // What's the final counter? Is it 1 (lost update) or 2 (correct)?
    // Does one transaction get SQLITE_BUSY? The library doesn't test this.
}
```

### No test for long-running transaction blocking the pool

With `threadCount(1)`, a transaction holds the only connection slot for its entire duration. Any other `db.update()` or `db.query()` call from another thread will block until `connectionTimeoutMs` and then throw. This is a critical operational footgun that users should understand, but there's no test demonstrating the behavior.

### Housekeeping tests are timing-dependent

All `HousekeepingTest` tests use `Thread.sleep(2_500)` to wait for background effects. These are inherently flaky on slow CI machines. The `housekeepingIntervalMs` is set to 100ms, but there's no synchronization barrier — the test trusts that 2.5 seconds is "enough".
