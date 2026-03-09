# Client API Assessment

## Public API Surface

### `SQLeicht` (main entry point)

| Method | Verdict | Notes |
|---|---|---|
| `create(String path)` | Good | Simple factory, sensible default config |
| `create(String path, SQLeichtConfig)` | Good | Overload for custom config |
| `execute(String sql)` | Okay | DDL-only intent unclear — can this run INSERT? Users will try |
| `update(String sql, Object...)` | Good | Returns change count, clear intent |
| `query(String sql, Object...)` | Good | Returns materialized rows |
| `forEach(String sql, RowConsumer, Object...)` | **Bad signature** | Consumer before params makes lambda usage ugly with params (see below) |
| `prepare(String sql)` | **Misleading** | Returns `SQLeichtStatement` which is NOT a prepared statement — just a parameter collector. No native stmt is allocated. Zero performance benefit over `update(sql, params)` |
| `lastInsertRowid()` | **Broken by design** | Connection-scoped state, but routes to a random slot. Useless with `threadCount > 1`. Offering it as a top-level method suggests it's safe to call standalone |
| `changes()` | **Broken by design** | Same problem as `lastInsertRowid()` |
| `batch(String sql, Iterable<Object[]>)` | Good | Auto-wraps in transaction |
| `transaction(TransactionFunction<T>)` | Good | Clean scoped transaction pattern |
| `submit(TaskFunction<T>)` | Okay | Necessary escape hatch, but exposes raw FFI handles via `SQLeichtConnection` |
| `activeCount()` / `idleCount()` / `pendingCount()` / `threadCount()` | Good | Pool observability |
| `close()` | Good | AutoCloseable |

### `SQLeichtTransaction`

| Method | Verdict | Notes |
|---|---|---|
| `execute(String sql)` | Good | Mirrors top-level |
| `update(String sql, Object...)` | Good | |
| `query(String sql, Object...)` | Good | |
| `forEach(String sql, RowConsumer, Object...)` | Same issue | Consumer before params |
| `batch(String sql, Iterator<Object[]>)` | Odd | `Iterator` is unusual — most users have `List`/`Iterable`. Overload exists but the `Iterator` one is public too |
| `batch(String sql, Iterable<Object[]>)` | Good | |
| `lastInsertRowid()` | Good | **Inside** a transaction this is safe — same connection |
| `changes()` | Good | Same — safe inside transaction |

### `SQLeichtRows`

| Method | Verdict | Notes |
|---|---|---|
| `size()` / `isEmpty()` | Good | |
| `get(int index)` | Good | |
| `columnNames()` / `columnCount()` | Good | |
| `iterator()` | Good | Iterable support |
| `close()` | **Misleading** | No-op. Users write try-with-resources blocks that do nothing |
| *(missing)* `first()` / `single()` | **Missing** | Extremely common pattern. Users always write `rows.get(0)` with no guard |

### `SQLeichtRow`

| Method | Verdict | Notes |
|---|---|---|
| `getInt(int)` / `getInt(String)` | **Unsafe** | NPE on NULL with no useful message. Must guard with `isNull()` first |
| `getLong(int)` / `getLong(String)` | **Unsafe** | Same |
| `getDouble(int)` / `getDouble(String)` | **Unsafe** | Same |
| `getText(int)` / `getText(String)` | Good | Null-safe — returns `null` |
| `getBlob(int)` / `getBlob(String)` | Good | Null-safe — returns `null` |
| `getSegment(int)` / `getSegment(String)` | Okay | Throws clear error when used outside `forEach`. Returns `MemorySegment.NULL` for null |
| `getLocalDate`, `getLocalTime`, `getLocalDateTime`, `getInstant`, `getOffsetDateTime` | Good | Null-safe, delegates through `getText` |
| `isNull(int)` / `isNull(String)` | Good | |
| *(missing)* `getBoolean` | **Missing** | SQLite stores bools as ints. Very common need |
| *(missing)* nullable primitive accessors | **Missing** | Would eliminate the NPE trap |

### `SQLeichtStatement`

| Method | Verdict | Notes |
|---|---|---|
| `bind(int, int/long/double/String/byte[]/temporal...)` | Okay | 1-based indexing matches SQLite, fluent chaining. But 11 overloads is a lot of surface area |
| `bindNull(int)` | Good | |
| `executeUpdate()` / `query()` | **Misleading** | Suggests executing a prepared statement. Actually just calls `db.update(sql, bindings.toArray())`. No native preparation, no caching, no performance benefit over inline params |
| `reset()` | **Misleading** | Just clears the Java list. Not a `sqlite3_reset` |

### `SQLeichtConfig`

| Method | Verdict | Notes |
|---|---|---|
| Fluent setters (`.threadCount(2).journalMode("WAL")`) | Okay | Convenient, but setter and getter share the same name — `threadCount(int)` sets, `threadCount()` gets. Unconventional, can confuse |
| `housekeepingIntervalMs` | **Inconsistent** | Package-private field with no setter, but has a public getter. Test-only backdoor leaking into the API |
| Sealing mechanism | Okay-ish | Mutable object that becomes immutable. A proper builder with `.build()` would be idiomatic Java |
| Validation in `validate()` | Okay | Called externally by `SQLeicht.create()`. Silently mutates config (`idleTimeoutMs = 0`) during "validation" — surprising side effect |

### Functional Interfaces

| Type | Verdict | Notes |
|---|---|---|
| `RowConsumer` | Okay | But only throws `SQLeichtException`. Users can't throw custom checked exceptions from `forEach` |
| `TransactionFunction<T>` | Okay | Same — locked to `SQLeichtException` |
| `TaskFunction<T>` | Okay | Lives in `core` package — inconsistent with the other two in the root package |

---

## Biggest usability problems, ranked

### 1. `forEach` parameter ordering kills lambda ergonomics

This is the API users will use most for performance. Compare:

```java
// Current — params awkwardly trail the lambda
db.forEach("SELECT * FROM t WHERE id > ?", row -> {
    process(row);
}, minId);

// What every other Java API does — callback last
db.forEach("SELECT * FROM t WHERE id > ?", minId, row -> {
    process(row);
});
```

Java's trailing-lambda convention is to put the functional argument last. Having `Object... params` last makes this impossible since varargs must be the final parameter. You'd need to split into `forEach(sql, consumer)` and `forEach(sql, Object[] params, consumer)` where params is an explicit `Object[]`.

### 2. `prepare()` / `SQLeichtStatement` is an illusion

It looks like a prepared statement but it's just a parameter accumulator. It allocates no native resources, provides no performance benefit, and its `reset()` doesn't call `sqlite3_reset`. A user who writes `var stmt = db.prepare(sql)` and reuses it in a loop thinking they're avoiding re-preparation overhead is getting zero benefit. The real prepared statement (`SQLeichtLiveStatement`) is hidden in the `core` package behind `submit()`. The name actively misleads.

### 3. `lastInsertRowid()` and `changes()` on `SQLeicht` are traps

They submit a new task that may hit a different connection slot than the one that did the INSERT/UPDATE. With `threadCount >= 2`, they return stale or wrong data. These should either not exist on `SQLeicht`, or the javadoc should scream that they only work inside `submit()` / `transaction()`. Currently they look like safe convenience methods.

### 4. Primitive getters NPE on NULL with no alternative

`getInt()` on a NULL column throws `NullPointerException` from an unboxing cast — not a descriptive library error. There's no `getInt(int col, int defaultValue)` or `Integer getIntOrNull(int col)`. Every primitive read requires a preceding `isNull()` check.

### 5. No `first()` / `single()` on `SQLeichtRows`

Nearly every query that returns one row ends up as `rows.get(0)` with no size guard. A `first()` (returns `Optional<SQLeichtRow>`) or `single()` (throws if not exactly 1) would prevent bugs and reduce boilerplate.

### 6. No void transaction overload

To do a transaction that doesn't return a value, users write:

```java
db.transaction(tx -> {
    tx.update(...);
    return null;  // annoying boilerplate
});
```

A `void transactionVoid(Consumer<SQLeichtTransaction>)` overload would be cleaner.

### 7. No `getBoolean()` on `SQLeichtRow`

SQLite stores booleans as integers. `getBoolean()` mapping `0 → false`, non-zero `→ true` is a very common need that every user will have to hand-roll.
