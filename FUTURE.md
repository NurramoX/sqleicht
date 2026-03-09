# Future: Larger-Effort SQLite APIs

APIs that would add significant value but require new subsystems or external dependencies.

## Serialize / Deserialize

`sqlite3_serialize` / `sqlite3_deserialize` — capture or restore entire databases as contiguous byte arrays. Enables "database as a value" patterns: snapshot transfer, zero-copy loading from memory-mapped files via FFM `MemorySegment`, shipping databases over the network. Default-enabled since SQLite 3.36.0. Incompatible with WAL mode.

## carray Extension (Batch Parameter Binding)

`sqlite3_carray_bind` / `sqlite3_carray_bind_v2` (3.52.0) — bind a native array directly as a table-valued function: `SELECT * FROM big_table WHERE id IN carray(?1)`. Eliminates dynamic IN-clause construction for batch lookups. Requires SQLite compiled with the carray extension. With FFM, Java arrays can be passed as native memory segments directly.

## Virtual Table API

`sqlite3_module` — allows custom data sources (Parquet, Arrow, CSV, REST APIs) to appear as native SQL tables. The IN-constraint optimization (3.38.0, `sqlite3_vtab_in`) and column pruning (`colUsed`) make this practical for data workloads. Large API surface area — would need a Java-friendly abstraction layer over xBestIndex/xFilter/xColumn callbacks.

## Session Extension (Changeset Replication)

`sqlite3session_create` / `sqlite3changeset_apply` — records all changes as compact binary changesets for delta-based replication. Requires compile flag `SQLITE_ENABLE_SESSION`. Useful for syncing embedded databases with central servers.

## Snapshot API

`sqlite3_snapshot_get` / `sqlite3_snapshot_open` — point-in-time consistent reads during concurrent writes. Enables "time travel" queries without blocking writers. Still marked experimental, requires compile flag `SQLITE_ENABLE_SNAPSHOT`.
