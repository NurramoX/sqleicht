package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public final class SQLiteConnectionHandle implements AutoCloseable {
  private final Arena arena;
  private MemorySegment db;

  SQLiteConnectionHandle(Arena arena, MemorySegment db) {
    this.arena = arena;
    this.db = db;
  }

  public static SQLiteConnectionHandle open(String filename, int flags) throws SQLeichtException {
    Arena arena = Arena.ofConfined();
    try {
      MemorySegment db = SQLiteNative.open(arena, filename, flags);
      return new SQLiteConnectionHandle(arena, db);
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

  public boolean isClosed() {
    return db == null;
  }

  @Override
  public void close() throws SQLeichtException {
    if (db != null) {
      MemorySegment toClose = db;
      db = null;
      SQLiteNative.close(toClose);
      arena.close();
    }
  }
}
