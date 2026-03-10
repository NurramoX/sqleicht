package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

final class StatementCache {
  private final MemorySegment db;
  private final int maxSize;
  private final LinkedHashMap<String, MemorySegment> cache;

  StatementCache(MemorySegment db, int maxSize) {
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

  MemorySegment acquire(String sql) throws SQLeichtException {
    if (maxSize > 0) {
      MemorySegment stmt = cache.remove(sql);
      if (stmt != null) {
        return stmt;
      }
    }
    return SQLiteNative.prepare(db, sql);
  }

  void release(String sql, MemorySegment stmt) {
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

  void close() {
    for (MemorySegment stmt : cache.values()) {
      SQLiteNative.finalizeStmt(stmt);
    }
    cache.clear();
  }
}
