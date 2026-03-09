package io.sqleicht;

import io.sqleicht.core.SQLeichtConnection;
import io.sqleicht.core.SQLeichtException;

public final class SQLeichtTransaction {
  private final SQLeichtConnection conn;

  SQLeichtTransaction(SQLeichtConnection conn) {
    this.conn = conn;
  }

  public void execute(String sql) throws SQLeichtException {
    conn.execute(sql);
  }

  public long update(String sql, Object... params) throws SQLeichtException {
    return conn.update(sql, params);
  }

  public SQLeichtRows query(String sql, Object... params) throws SQLeichtException {
    return conn.query(sql, params);
  }

  public void forEach(String sql, RowConsumer consumer, Object... params) throws SQLeichtException {
    conn.forEach(sql, consumer, params);
  }

  public long batch(String sql, Iterable<Object[]> rows) throws SQLeichtException {
    return conn.batch(sql, rows.iterator());
  }

  public long lastInsertRowid() {
    return conn.lastInsertRowid();
  }

  public long changes() {
    return conn.changes();
  }
}
