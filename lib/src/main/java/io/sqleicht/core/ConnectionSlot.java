package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class ConnectionSlot {
  static final int NOT_IN_USE = 0;
  static final int IN_USE = 1;
  static final int RESERVED = -2;
  static final int REMOVED = -1;

  private static final AtomicIntegerFieldUpdater<ConnectionSlot> STATE =
      AtomicIntegerFieldUpdater.newUpdater(ConnectionSlot.class, "state");

  private final String path;
  private final int openFlags;
  private final int busyTimeoutMs;
  private final String journalMode;
  private final String connectionInitSql;

  volatile int state;
  volatile boolean evict;
  volatile long lastAccessed;
  volatile long createdAt;

  private SQLiteConnectionHandle connection;

  public ConnectionSlot(
      String path, int openFlags, int busyTimeoutMs, String journalMode, String connectionInitSql) {
    this.path = path;
    this.openFlags = openFlags;
    this.busyTimeoutMs = busyTimeoutMs;
    this.journalMode = journalMode;
    this.connectionInitSql = connectionInitSql;
  }

  public void openConnection() throws SQLeichtException {
    connection = SQLiteConnectionHandle.open(path, openFlags);
    createdAt = System.nanoTime();
    lastAccessed = createdAt;

    Arena arena = connection.arena();
    MemorySegment db = connection.db();

    SQLiteNative.busyTimeout(db, busyTimeoutMs);

    if (journalMode != null && !journalMode.isEmpty()) {
      SQLiteNative.exec(arena, db, "PRAGMA journal_mode=" + journalMode);
    }

    if (connectionInitSql != null && !connectionInitSql.isEmpty()) {
      SQLiteNative.exec(arena, db, connectionInitSql);
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

  public SQLiteConnectionHandle connection() {
    return connection;
  }

  public boolean casState(int expect, int update) {
    return STATE.compareAndSet(this, expect, update);
  }

  public int getState() {
    return state;
  }
}
