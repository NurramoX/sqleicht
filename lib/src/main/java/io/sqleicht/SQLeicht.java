package io.sqleicht;

import io.sqleicht.core.ConnectionExecutor;
import io.sqleicht.core.SQLeichtConnection;
import io.sqleicht.core.SQLeichtException;
import io.sqleicht.core.TaskFunction;

public final class SQLeicht implements AutoCloseable {
  private final ConnectionExecutor executor;

  private SQLeicht(ConnectionExecutor executor) {
    this.executor = executor;
  }

  public static SQLeicht create(String path) {
    return create(path, new SQLeichtConfig());
  }

  public static SQLeicht create(String path, SQLeichtConfig config) {
    config.validate();
    config.seal();
    ConnectionExecutor executor = new ConnectionExecutor(path, config);
    return new SQLeicht(executor);
  }

  public void execute(String sql) throws SQLeichtException {
    executor.submit(
        conn -> {
          conn.execute(sql);
          return null;
        });
  }

  public long update(String sql, Object... params) throws SQLeichtException {
    return executor.submit(conn -> conn.update(sql, params));
  }

  public SQLeichtRows query(String sql, Object... params) throws SQLeichtException {
    return executor.submit(conn -> conn.query(sql, params));
  }

  public void forEach(String sql, RowConsumer consumer, Object... params) throws SQLeichtException {
    executor.submit(
        conn -> {
          conn.forEach(sql, consumer, params);
          return null;
        });
  }

  public SQLeichtStatement prepare(String sql) {
    return new SQLeichtStatement(this, sql);
  }

  public long lastInsertRowid() throws SQLeichtException {
    return executor.submit(SQLeichtConnection::lastInsertRowid);
  }

  public long changes() throws SQLeichtException {
    return executor.submit(SQLeichtConnection::changes);
  }

  public long batch(String sql, Iterable<Object[]> rows) throws SQLeichtException {
    return transaction(tx -> tx.batch(sql, rows));
  }

  public <T> T transaction(TransactionFunction<T> fn) throws SQLeichtException {
    return executor.submit(
        conn -> {
          conn.execute("BEGIN");
          try {
            T result = fn.apply(new SQLeichtTransaction(conn));
            conn.execute("COMMIT");
            return result;
          } catch (Throwable t) {
            try {
              conn.execute("ROLLBACK");
            } catch (SQLeichtException rollbackErr) {
              t.addSuppressed(rollbackErr);
            }
            if (t instanceof SQLeichtException se) throw se;
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new SQLeichtException(0, 0, "Transaction failed: " + t.getMessage());
          }
        });
  }

  public <T> T submit(TaskFunction<T> task) throws SQLeichtException {
    return executor.submit(task);
  }

  public int activeCount() {
    return executor.activeCount();
  }

  public int idleCount() {
    return executor.idleCount();
  }

  public int pendingCount() {
    return executor.pendingCount();
  }

  public int threadCount() {
    return executor.threadCount();
  }

  @Override
  public void close() {
    executor.close();
  }
}
