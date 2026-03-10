package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.MemorySegment;

public final class SQLiteStatementHandle implements AutoCloseable {
  private final SQLiteConnectionHandle connection;
  private MemorySegment stmt;

  SQLiteStatementHandle(SQLiteConnectionHandle connection, MemorySegment stmt) {
    this.connection = connection;
    this.stmt = stmt;
  }

  public static SQLiteStatementHandle prepare(SQLiteConnectionHandle connection, String sql)
      throws SQLeichtException {
    MemorySegment stmt = SQLiteNative.prepare(connection.db(), sql);
    return new SQLiteStatementHandle(connection, stmt);
  }

  MemorySegment stmt() {
    if (isClosed()) {
      throw new IllegalStateException("Statement is closed");
    }
    return stmt;
  }

  SQLiteConnectionHandle connection() {
    return connection;
  }

  boolean isClosed() {
    return stmt == null;
  }

  @Override
  public void close() {
    if (stmt != null) {
      MemorySegment toFinalize = stmt;
      stmt = null;
      SQLiteNative.finalizeStmt(toFinalize);
    }
  }
}
