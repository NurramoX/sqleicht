package io.sqleicht;

import io.sqleicht.core.ConnectionExecutor;
import io.sqleicht.core.SQLeichtConnection;
import io.sqleicht.core.SQLeichtException;
import io.sqleicht.core.SQLiteColumnType;
import io.sqleicht.core.SQLiteResultCode;
import io.sqleicht.core.TaskFunction;
import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    return executeUpdate(sql, params);
  }

  public SQLeichtRows query(String sql, Object... params) throws SQLeichtException {
    return executeQuery(sql, params);
  }

  public void forEach(String sql, RowConsumer consumer, Object... params) throws SQLeichtException {
    executor.submit(
        conn -> {
          try (var arena = Arena.ofConfined()) {
            MemorySegment db = conn.db();
            MemorySegment stmt = SQLiteNative.prepare(arena, db, sql);
            try {
              bindParams(arena, stmt, params);

              int colCount = SQLiteNative.columnCount(stmt);

              String[] columnNames = new String[colCount];
              for (int i = 0; i < colCount; i++) {
                columnNames[i] = SQLiteNative.columnName(stmt, i);
              }

              Map<String, Integer> nameIndex = new HashMap<>(colCount);
              for (int i = 0; i < colCount; i++) {
                nameIndex.put(columnNames[i], i);
              }

              int[] columnTypes = new int[colCount];

              while (SQLiteNative.step(stmt) == SQLiteResultCode.ROW.code()) {
                for (int i = 0; i < colCount; i++) {
                  columnTypes[i] = SQLiteNative.columnType(stmt, i);
                }

                Object[] values = new Object[colCount];
                for (int c = 0; c < colCount; c++) {
                  values[c] = readColumnZeroCopy(stmt, c, columnTypes[c]);
                }

                consumer.accept(new SQLeichtRow(values, columnTypes, nameIndex));
              }
            } finally {
              SQLiteNative.finalizeStmt(stmt);
            }
          }
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

  long executeUpdate(String sql, Object[] params) throws SQLeichtException {
    return executor.submit(
        conn -> {
          try (var arena = Arena.ofConfined()) {
            MemorySegment db = conn.db();
            MemorySegment stmt = SQLiteNative.prepare(arena, db, sql);
            try {
              bindParams(arena, stmt, params);
              SQLiteNative.step(stmt);
              return SQLiteNative.changes(db);
            } finally {
              SQLiteNative.finalizeStmt(stmt);
            }
          }
        });
  }

  SQLeichtRows executeQuery(String sql, Object[] params) throws SQLeichtException {
    return executor.submit(
        conn -> {
          Arena resultArena = Arena.ofShared();
          try {
            try (var taskArena = Arena.ofConfined()) {
              MemorySegment db = conn.db();
              MemorySegment stmt = SQLiteNative.prepare(taskArena, db, sql);
              try {
                bindParams(taskArena, stmt, params);

                int colCount = SQLiteNative.columnCount(stmt);

                // Read column names and types on first row
                String[] columnNames = new String[colCount];
                for (int i = 0; i < colCount; i++) {
                  columnNames[i] = SQLiteNative.columnName(stmt, i);
                }

                Map<String, Integer> nameIndex = new HashMap<>(colCount);
                for (int i = 0; i < colCount; i++) {
                  nameIndex.put(columnNames[i], i);
                }

                List<SQLeichtRow> rows = new ArrayList<>();
                int[] columnTypes = null;

                while (SQLiteNative.step(stmt) == SQLiteResultCode.ROW.code()) {
                  if (columnTypes == null) {
                    columnTypes = new int[colCount];
                    for (int i = 0; i < colCount; i++) {
                      columnTypes[i] = SQLiteNative.columnType(stmt, i);
                    }
                  }

                  Object[] values = new Object[colCount];
                  for (int c = 0; c < colCount; c++) {
                    int type = SQLiteNative.columnType(stmt, c);
                    values[c] = readColumn(resultArena, stmt, c, type);
                  }

                  rows.add(new SQLeichtRow(values, columnTypes, nameIndex));
                }

                if (columnTypes == null) {
                  columnTypes = new int[colCount];
                }

                return new SQLeichtRows(resultArena, rows, columnNames, columnTypes, nameIndex);
              } finally {
                SQLiteNative.finalizeStmt(stmt);
              }
            }
          } catch (Throwable t) {
            resultArena.close();
            throw t;
          }
        });
  }

  static Object readColumnZeroCopy(MemorySegment stmt, int col, int type) {
    return switch (type) {
      case SQLiteColumnType.INTEGER -> SQLiteNative.columnLong(stmt, col);
      case SQLiteColumnType.FLOAT -> SQLiteNative.columnDouble(stmt, col);
      case SQLiteColumnType.TEXT -> {
        MemorySegment seg = SQLiteNative.columnTextSegment(stmt, col);
        yield seg.equals(MemorySegment.NULL) ? null : seg;
      }
      case SQLiteColumnType.BLOB -> {
        MemorySegment seg = SQLiteNative.columnBlobSegment(stmt, col);
        yield seg.equals(MemorySegment.NULL) ? null : seg;
      }
      case SQLiteColumnType.NULL -> null;
      default -> null;
    };
  }

  static Object readColumn(Arena resultArena, MemorySegment stmt, int col, int type) {
    return switch (type) {
      case SQLiteColumnType.INTEGER -> SQLiteNative.columnLong(stmt, col);
      case SQLiteColumnType.FLOAT -> SQLiteNative.columnDouble(stmt, col);
      case SQLiteColumnType.TEXT -> {
        MemorySegment src = SQLiteNative.columnTextSegment(stmt, col);
        if (src.equals(MemorySegment.NULL)) yield null;
        MemorySegment dst = resultArena.allocate(src.byteSize());
        MemorySegment.copy(src, 0, dst, 0, src.byteSize());
        yield dst;
      }
      case SQLiteColumnType.BLOB -> {
        MemorySegment src = SQLiteNative.columnBlobSegment(stmt, col);
        if (src.equals(MemorySegment.NULL)) yield null;
        MemorySegment dst = resultArena.allocate(src.byteSize());
        MemorySegment.copy(src, 0, dst, 0, src.byteSize());
        yield dst;
      }
      case SQLiteColumnType.NULL -> null;
      default -> null;
    };
  }

  static void bindParams(Arena arena, MemorySegment stmt, Object[] params)
      throws SQLeichtException {
    if (params == null) return;
    for (int i = 0; i < params.length; i++) {
      int idx = i + 1; // 1-based
      Object p = params[i];
      switch (p) {
        case null -> SQLiteNative.bindNull(stmt, idx);
        case Integer v -> SQLiteNative.bindInt(stmt, idx, v);
        case Long v -> SQLiteNative.bindLong(stmt, idx, v);
        case Double v -> SQLiteNative.bindDouble(stmt, idx, v);
        case String v -> SQLiteNative.bindText(arena, stmt, idx, v);
        case byte[] v -> SQLiteNative.bindBlob(arena, stmt, idx, v);
        case LocalDate v -> SQLiteNative.bindText(arena, stmt, idx, v.toString());
        case LocalTime v -> SQLiteNative.bindText(arena, stmt, idx, v.toString());
        case LocalDateTime v -> SQLiteNative.bindText(arena, stmt, idx, v.toString());
        case Instant v -> SQLiteNative.bindText(arena, stmt, idx, v.toString());
        case OffsetDateTime v -> SQLiteNative.bindText(arena, stmt, idx, v.toString());
        case ZonedDateTime v ->
            SQLiteNative.bindText(arena, stmt, idx, v.toOffsetDateTime().toString());
        default ->
            throw new SQLeichtException(
                0, 0, "Unsupported parameter type: " + p.getClass().getName() + " at index " + idx);
      }
    }
  }
}
