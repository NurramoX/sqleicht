package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public final class SQLiteConnectionHandle implements AutoCloseable {
  private final Arena arena;
  private MemorySegment db;
  private final StatementCache stmtCache;

  SQLiteConnectionHandle(Arena arena, MemorySegment db, StatementCache stmtCache) {
    this.arena = arena;
    this.db = db;
    this.stmtCache = stmtCache;
  }

  public static SQLiteConnectionHandle open(String filename, int flags, int stmtCacheSize)
      throws SQLeichtException {
    Arena arena = Arena.ofShared();
    try {
      MemorySegment db = SQLiteNative.open(arena, filename, flags);
      StatementCache cache = new StatementCache(arena, db, stmtCacheSize);
      return new SQLiteConnectionHandle(arena, db, cache);
    } catch (Throwable t) {
      arena.close();
      throw t;
    }
  }

  public MemorySegment db() {
    if (isClosed()) {
      throw new IllegalStateException("Connection is closed");
    }
    return db;
  }

  public Arena arena() {
    return arena;
  }

  public StatementCache stmtCache() {
    return stmtCache;
  }

  public boolean isClosed() {
    return db == null;
  }

  @Override
  public void close() throws SQLeichtException {
    if (db != null) {
      MemorySegment toClose = db;
      db = null;
      stmtCache.close();
      SQLiteNative.close(toClose);
      arena.close();
    }
  }
}
