package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public final class SQLeichtConnection {
  private final SQLiteConnectionHandle handle;

  SQLeichtConnection(SQLiteConnectionHandle handle) {
    this.handle = handle;
  }

  public void execute(String sql) throws SQLeichtException {
    SQLiteNative.exec(handle.arena(), handle.db(), sql);
  }

  public SQLeichtLiveStatement prepare(String sql) throws SQLeichtException {
    SQLiteStatementHandle stmt = SQLiteStatementHandle.prepare(handle, sql);
    return new SQLeichtLiveStatement(handle, stmt);
  }

  public long lastInsertRowid() {
    return SQLiteNative.lastInsertRowid(handle.db());
  }

  public long changes() {
    return SQLiteNative.changes(handle.db());
  }

  public Arena arena() {
    return handle.arena();
  }

  public MemorySegment db() {
    return handle.db();
  }
}
