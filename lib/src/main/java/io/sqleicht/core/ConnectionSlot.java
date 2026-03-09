package io.sqleicht.core;

import io.sqleicht.SQLeichtConfig;
import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.locks.ReentrantLock;

public final class ConnectionSlot {
  private final ReentrantLock lock = new ReentrantLock();
  private final String path;
  private final int openFlags;
  private final SQLeichtConfig config;

  volatile boolean evict;
  volatile long lastAccessed;
  volatile long createdAt;

  private SQLiteConnectionHandle connection;

  public ConnectionSlot(String path, int openFlags, SQLeichtConfig config) {
    this.path = path;
    this.openFlags = openFlags;
    this.config = config;
  }

  public void openConnection() throws SQLeichtException {
    connection = SQLiteConnectionHandle.open(path, openFlags, config.statementCacheSize());
    createdAt = System.nanoTime();
    lastAccessed = createdAt;

    Arena arena = connection.arena();
    MemorySegment db = connection.db();

    // Page-level settings (only effective on new databases)
    SQLiteNative.exec(arena, db, "PRAGMA page_size=" + config.pageSize());
    SQLiteNative.exec(arena, db, "PRAGMA auto_vacuum=" + config.autoVacuum());

    // Journal and sync
    String journalMode = config.journalMode();
    if (journalMode != null && !journalMode.isEmpty()) {
      SQLiteNative.exec(arena, db, "PRAGMA journal_mode=" + journalMode);
    }
    SQLiteNative.busyTimeout(db, config.busyTimeoutMs());
    SQLiteNative.exec(arena, db, "PRAGMA synchronous=" + config.synchronous());

    // Cache and memory
    SQLiteNative.exec(arena, db, "PRAGMA cache_size=" + config.cacheSize());
    SQLiteNative.exec(arena, db, "PRAGMA mmap_size=" + config.mmapSize());
    SQLiteNative.exec(arena, db, "PRAGMA temp_store=" + config.tempStore());

    // Features
    SQLiteNative.exec(arena, db, "PRAGMA foreign_keys=" + (config.foreignKeys() ? "ON" : "OFF"));

    // User custom SQL (runs last so it can override anything)
    String initSql = config.connectionInitSql();
    if (initSql != null && !initSql.isEmpty()) {
      SQLiteNative.exec(arena, db, initSql);
    }
  }

  public void closeConnection() {
    if (connection != null && !connection.isClosed()) {
      try {
        connection.close();
      } catch (SQLeichtException e) {
        // best effort
      }
      connection = null;
    }
  }

  public void rotate() throws SQLeichtException {
    closeConnection();
    openConnection();
    evict = false;
  }

  public boolean isConnectionOpen() {
    return connection != null && !connection.isClosed();
  }

  public SQLiteConnectionHandle connection() {
    return connection;
  }

  public ReentrantLock lock() {
    return lock;
  }
}
