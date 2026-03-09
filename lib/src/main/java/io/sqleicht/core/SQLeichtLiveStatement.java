package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.MemorySegment;

public final class SQLeichtLiveStatement implements AutoCloseable {
  private final SQLiteConnectionHandle connection;
  private final SQLiteStatementHandle handle;

  SQLeichtLiveStatement(SQLiteConnectionHandle connection, SQLiteStatementHandle handle) {
    this.connection = connection;
    this.handle = handle;
  }

  public SQLeichtLiveStatement bindInt(int index, int value) throws SQLeichtException {
    SQLiteNative.bindInt(handle.stmt(), index, value);
    return this;
  }

  public SQLeichtLiveStatement bindLong(int index, long value) throws SQLeichtException {
    SQLiteNative.bindLong(handle.stmt(), index, value);
    return this;
  }

  public SQLeichtLiveStatement bindDouble(int index, double value) throws SQLeichtException {
    SQLiteNative.bindDouble(handle.stmt(), index, value);
    return this;
  }

  public SQLeichtLiveStatement bindText(int index, String value) throws SQLeichtException {
    SQLiteNative.bindText(connection.arena(), handle.stmt(), index, value);
    return this;
  }

  public SQLeichtLiveStatement bindBlob(int index, byte[] value) throws SQLeichtException {
    SQLiteNative.bindBlob(connection.arena(), handle.stmt(), index, value);
    return this;
  }

  public SQLeichtLiveStatement bindNull(int index) throws SQLeichtException {
    SQLiteNative.bindNull(handle.stmt(), index);
    return this;
  }

  public boolean step() {
    int rc = SQLiteNative.step(handle.stmt());
    return rc == SQLiteResultCode.ROW.code();
  }

  public void reset() throws SQLeichtException {
    SQLiteNative.reset(handle.stmt());
    SQLiteNative.clearBindings(handle.stmt());
  }

  public int columnInt(int col) {
    return SQLiteNative.columnInt(handle.stmt(), col);
  }

  public long columnLong(int col) {
    return SQLiteNative.columnLong(handle.stmt(), col);
  }

  public double columnDouble(int col) {
    return SQLiteNative.columnDouble(handle.stmt(), col);
  }

  public String columnText(int col) {
    return SQLiteNative.columnText(handle.stmt(), col);
  }

  public byte[] columnBlob(int col) {
    return SQLiteNative.columnBlob(handle.stmt(), col);
  }

  public MemorySegment columnTextSegment(int col) {
    return SQLiteNative.columnTextSegment(handle.stmt(), col);
  }

  public MemorySegment columnBlobSegment(int col) {
    return SQLiteNative.columnBlobSegment(handle.stmt(), col);
  }

  public int columnCount() {
    return SQLiteNative.columnCount(handle.stmt());
  }

  public String columnName(int col) {
    return SQLiteNative.columnName(handle.stmt(), col);
  }

  public int columnType(int col) {
    return SQLiteNative.columnType(handle.stmt(), col);
  }

  public int stmtStatus(int op, boolean reset) {
    return SQLiteNative.stmtStatus(handle.stmt(), op, reset);
  }

  @Override
  public void close() {
    handle.close();
  }
}
