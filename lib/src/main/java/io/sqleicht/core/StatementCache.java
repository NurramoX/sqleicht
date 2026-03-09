package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StatementCache {
  private final Arena arena;
  private final MemorySegment db;
  private final int maxSize;
  private final LinkedHashMap<String, MemorySegment> cache;

  StatementCache(Arena arena, MemorySegment db, int maxSize) {
    this.arena = arena;
    this.db = db;
    this.maxSize = maxSize;
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, MemorySegment> eldest) {
            if (size() > maxSize) {
              SQLiteNative.finalizeStmt(eldest.getValue());
              return true;
            }
            return false;
          }
        };
  }

  public MemorySegment acquire(String sql) throws SQLeichtException {
    if (maxSize > 0) {
      MemorySegment stmt = cache.remove(sql);
      if (stmt != null) {
        return stmt;
      }
    }
    return SQLiteNative.prepare(arena, db, sql);
  }

  public void release(String sql, MemorySegment stmt) {
    if (maxSize == 0) {
      SQLiteNative.finalizeStmt(stmt);
      return;
    }
    try {
      SQLiteNative.reset(stmt);
      SQLiteNative.clearBindings(stmt);
    } catch (SQLeichtException e) {
      SQLiteNative.finalizeStmt(stmt);
      return;
    }
    MemorySegment prev = cache.put(sql, stmt);
    if (prev != null) {
      SQLiteNative.finalizeStmt(prev);
    }
  }

  public void close() {
    for (MemorySegment stmt : cache.values()) {
      SQLiteNative.finalizeStmt(stmt);
    }
    cache.clear();
  }
}
