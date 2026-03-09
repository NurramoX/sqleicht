package io.sqleicht;

import io.sqleicht.core.SQLeichtConnection;
import io.sqleicht.core.SQLeichtException;
import io.sqleicht.core.SQLiteResultCode;
import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class SQLeichtTransaction {
  private final SQLeichtConnection conn;

  SQLeichtTransaction(SQLeichtConnection conn) {
    this.conn = conn;
  }

  public void execute(String sql) throws SQLeichtException {
    conn.execute(sql);
  }

  public long update(String sql, Object... params) throws SQLeichtException {
    MemorySegment stmt = conn.stmtCache().acquire(sql);
    try {
      try (var arena = Arena.ofConfined()) {
        SQLeicht.bindParams(arena, stmt, params);
        SQLiteNative.step(stmt);
      }
      return SQLiteNative.changes(conn.db());
    } finally {
      conn.stmtCache().release(sql, stmt);
    }
  }

  public SQLeichtRows query(String sql, Object... params) throws SQLeichtException {
    MemorySegment stmt = conn.stmtCache().acquire(sql);
    try {
      try (var arena = Arena.ofConfined()) {
        SQLeicht.bindParams(arena, stmt, params);

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
            values[c] = SQLeicht.readColumnEager(stmt, c, type);
          }

          rows.add(new SQLeichtRow(values, columnTypes, nameIndex));
        }

        if (columnTypes == null) {
          columnTypes = new int[colCount];
        }

        return new SQLeichtRows(rows, columnNames, columnTypes, nameIndex);
      }
    } finally {
      conn.stmtCache().release(sql, stmt);
    }
  }

  public void forEach(String sql, RowConsumer consumer, Object... params) throws SQLeichtException {
    MemorySegment stmt = conn.stmtCache().acquire(sql);
    try {
      try (var arena = Arena.ofConfined()) {
        SQLeicht.bindParams(arena, stmt, params);

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
            values[c] = SQLeicht.readColumnZeroCopy(stmt, c, columnTypes[c]);
          }

          consumer.accept(new SQLeichtRow(values, columnTypes, nameIndex));
        }
      }
    } finally {
      conn.stmtCache().release(sql, stmt);
    }
  }

  public long batch(String sql, Iterator<Object[]> rows) throws SQLeichtException {
    long totalChanges = 0;
    MemorySegment db = conn.db();
    MemorySegment stmt = conn.stmtCache().acquire(sql);
    try {
      while (rows.hasNext()) {
        Object[] params = rows.next();
        try (var bindArena = Arena.ofConfined()) {
          SQLeicht.bindParams(bindArena, stmt, params);
          SQLiteNative.step(stmt);
          totalChanges += SQLiteNative.changes(db);
        }
        SQLiteNative.reset(stmt);
        SQLiteNative.clearBindings(stmt);
      }
    } finally {
      conn.stmtCache().release(sql, stmt);
    }
    return totalChanges;
  }

  public long batch(String sql, Iterable<Object[]> rows) throws SQLeichtException {
    return batch(sql, rows.iterator());
  }

  public long lastInsertRowid() {
    return conn.lastInsertRowid();
  }

  public long changes() {
    return conn.changes();
  }
}
