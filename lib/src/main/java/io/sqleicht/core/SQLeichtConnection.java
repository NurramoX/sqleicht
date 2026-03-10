package io.sqleicht.core;

import io.sqleicht.RowConsumer;
import io.sqleicht.SQLeichtRow;
import io.sqleicht.SQLeichtRows;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

  public long update(String sql, Object... params) throws SQLeichtException {
    MemorySegment db = handle.db();
    MemorySegment stmt = handle.stmtCache().acquire(sql);
    try {
      try (var arena = Arena.ofConfined()) {
        bindParams(arena, stmt, params);
        SQLiteNative.stepUpdate(db, stmt);
      }
      return SQLiteNative.changes(db);
    } finally {
      handle.stmtCache().release(sql, stmt);
    }
  }

  public SQLeichtRows query(String sql, Object... params) throws SQLeichtException {
    MemorySegment stmt = handle.stmtCache().acquire(sql);
    try {
      try (var arena = Arena.ofConfined()) {
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

        List<SQLeichtRow> rows = new ArrayList<>();

        int rc;
        while ((rc = SQLiteNative.step(stmt)) == SQLiteResultCode.ROW.code()) {
          int[] columnTypes = new int[colCount];
          Object[] values = new Object[colCount];
          for (int c = 0; c < colCount; c++) {
            columnTypes[c] = SQLiteNative.columnType(stmt, c);
            values[c] = readColumnEager(stmt, c, columnTypes[c]);
          }
          rows.add(new SQLeichtRow(values, columnTypes, nameIndex));
        }
        if (rc != SQLiteResultCode.DONE.code()) {
          throw SQLeichtException.fromConnection(handle.db(), rc);
        }

        return new SQLeichtRows(rows, columnNames);
      }
    } finally {
      handle.stmtCache().release(sql, stmt);
    }
  }

  public void forEach(String sql, RowConsumer consumer) throws SQLeichtException {
    forEach(sql, null, consumer);
  }

  public void forEach(String sql, Object[] params, RowConsumer consumer) throws SQLeichtException {
    MemorySegment stmt = handle.stmtCache().acquire(sql);
    try {
      try (var arena = Arena.ofConfined()) {
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

        int rc;
        while ((rc = SQLiteNative.step(stmt)) == SQLiteResultCode.ROW.code()) {
          int[] columnTypes = new int[colCount];
          Object[] values = new Object[colCount];
          for (int c = 0; c < colCount; c++) {
            columnTypes[c] = SQLiteNative.columnType(stmt, c);
            values[c] = readColumnZeroCopy(stmt, c, columnTypes[c]);
          }
          consumer.accept(new SQLeichtRow(values, columnTypes, nameIndex));
        }
        if (rc != SQLiteResultCode.DONE.code()) {
          throw SQLeichtException.fromConnection(handle.db(), rc);
        }
      }
    } finally {
      handle.stmtCache().release(sql, stmt);
    }
  }

  public long batch(String sql, Iterator<Object[]> rows) throws SQLeichtException {
    long totalChanges = 0;
    MemorySegment db = handle.db();
    MemorySegment stmt = handle.stmtCache().acquire(sql);
    try {
      while (rows.hasNext()) {
        Object[] params = rows.next();
        try (var bindArena = Arena.ofConfined()) {
          bindParams(bindArena, stmt, params);
          SQLiteNative.stepUpdate(db, stmt);
          totalChanges += SQLiteNative.changes(db);
        }
        SQLiteNative.reset(stmt);
        SQLiteNative.clearBindings(stmt);
      }
    } finally {
      handle.stmtCache().release(sql, stmt);
    }
    return totalChanges;
  }

  public long lastInsertRowid() {
    return SQLiteNative.lastInsertRowid(handle.db());
  }

  public long changes() {
    return SQLiteNative.changes(handle.db());
  }

  public SQLiteNative.ColumnMetadata tableColumnMetadata(
      String dbName, String tableName, String columnName) throws SQLeichtException {
    return SQLiteNative.tableColumnMetadata(handle.db(), dbName, tableName, columnName);
  }

  public SQLiteNative.ColumnMetadata tableColumnMetadata(String tableName, String columnName)
      throws SQLeichtException {
    return tableColumnMetadata(null, tableName, columnName);
  }

  int connectionId() {
    return System.identityHashCode(handle);
  }

  // === Internal helpers — not exposed to users ===

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
        case Float v -> SQLiteNative.bindDouble(stmt, idx, v.doubleValue());
        case Short v -> SQLiteNative.bindInt(stmt, idx, v.intValue());
        case Byte v -> SQLiteNative.bindInt(stmt, idx, v.intValue());
        case Boolean v -> SQLiteNative.bindInt(stmt, idx, v ? 1 : 0);
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

  private static Object readColumnEager(MemorySegment stmt, int col, int type) {
    return switch (type) {
      case SQLiteColumnType.INTEGER -> SQLiteNative.columnLong(stmt, col);
      case SQLiteColumnType.FLOAT -> SQLiteNative.columnDouble(stmt, col);
      case SQLiteColumnType.TEXT -> SQLiteNative.columnText(stmt, col);
      case SQLiteColumnType.BLOB -> SQLiteNative.columnBlob(stmt, col);
      case SQLiteColumnType.NULL -> null;
      default -> null;
    };
  }

  private static Object readColumnZeroCopy(MemorySegment stmt, int col, int type) {
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
}
