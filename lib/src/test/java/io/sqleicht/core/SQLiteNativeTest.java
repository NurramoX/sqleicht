package io.sqleicht.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class SQLiteNativeTest {

  @Test
  void libversionReturnsNonNull() {
    String version = SQLiteNative.libversion();
    assertNotNull(version);
    assertTrue(version.startsWith("3."), "Expected SQLite 3.x, got: " + version);
  }

  @Test
  void openAndCloseInMemoryDatabase() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      assertNotNull(conn.db());
    }
  }

  @Test
  void createTableInsertAndSelect() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      // Create table
      SQLiteNative.exec(
          conn.arena(),
          conn.db(),
          "CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT, value REAL)");

      // Insert with prepared statement
      try (var insertStmt =
          SQLiteStatementHandle.prepare(conn, "INSERT INTO test VALUES (?, ?, ?)")) {
        SQLiteNative.bindInt(insertStmt.stmt(), 1, 1);
        SQLiteNative.bindText(conn.arena(), insertStmt.stmt(), 2, "hello");
        SQLiteNative.bindDouble(insertStmt.stmt(), 3, 3.14);

        int rc = SQLiteNative.step(insertStmt.stmt());
        assertEquals(SQLiteResultCode.DONE.code(), rc);
      }

      // Insert another row
      try (var insertStmt =
          SQLiteStatementHandle.prepare(conn, "INSERT INTO test VALUES (?, ?, ?)")) {
        SQLiteNative.bindLong(insertStmt.stmt(), 1, 2L);
        SQLiteNative.bindText(conn.arena(), insertStmt.stmt(), 2, "world");
        SQLiteNative.bindNull(insertStmt.stmt(), 3);

        int rc = SQLiteNative.step(insertStmt.stmt());
        assertEquals(SQLiteResultCode.DONE.code(), rc);
      }

      // Verify changes count
      assertEquals(1, SQLiteNative.changes(conn.db()));

      // Select and read columns
      try (var selectStmt = SQLiteStatementHandle.prepare(conn, "SELECT * FROM test ORDER BY id")) {
        assertEquals(3, SQLiteNative.columnCount(selectStmt.stmt()));

        // Column names
        assertEquals("id", SQLiteNative.columnName(selectStmt.stmt(), 0));
        assertEquals("name", SQLiteNative.columnName(selectStmt.stmt(), 1));
        assertEquals("value", SQLiteNative.columnName(selectStmt.stmt(), 2));

        // First row
        int rc = SQLiteNative.step(selectStmt.stmt());
        assertEquals(SQLiteResultCode.ROW.code(), rc);
        assertEquals(1, SQLiteNative.columnInt(selectStmt.stmt(), 0));
        assertEquals("hello", SQLiteNative.columnText(selectStmt.stmt(), 1));
        assertEquals(3.14, SQLiteNative.columnDouble(selectStmt.stmt(), 2), 0.001);

        // Column types
        assertEquals(SQLiteColumnType.INTEGER, SQLiteNative.columnType(selectStmt.stmt(), 0));
        assertEquals(SQLiteColumnType.TEXT, SQLiteNative.columnType(selectStmt.stmt(), 1));
        assertEquals(SQLiteColumnType.FLOAT, SQLiteNative.columnType(selectStmt.stmt(), 2));

        // Second row
        rc = SQLiteNative.step(selectStmt.stmt());
        assertEquals(SQLiteResultCode.ROW.code(), rc);
        assertEquals(2L, SQLiteNative.columnLong(selectStmt.stmt(), 0));
        assertEquals("world", SQLiteNative.columnText(selectStmt.stmt(), 1));
        assertEquals(SQLiteColumnType.NULL, SQLiteNative.columnType(selectStmt.stmt(), 2));

        // No more rows
        rc = SQLiteNative.step(selectStmt.stmt());
        assertEquals(SQLiteResultCode.DONE.code(), rc);
      }
    }
  }

  @Test
  void blobRoundTrip() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE blobs (data BLOB)");

      byte[] original = {0x01, 0x02, 0x03, (byte) 0xFF, 0x00, (byte) 0xAB};
      try (var insertStmt = SQLiteStatementHandle.prepare(conn, "INSERT INTO blobs VALUES (?)")) {
        SQLiteNative.bindBlob(conn.arena(), insertStmt.stmt(), 1, original);
        SQLiteNative.step(insertStmt.stmt());
      }

      try (var selectStmt = SQLiteStatementHandle.prepare(conn, "SELECT data FROM blobs")) {
        SQLiteNative.step(selectStmt.stmt());
        byte[] result = SQLiteNative.columnBlob(selectStmt.stmt(), 0);
        assertArrayEquals(original, result);
        assertEquals(original.length, SQLiteNative.columnBytes(selectStmt.stmt(), 0));
      }
    }
  }

  @Test
  void lastInsertRowid() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE t (id INTEGER PRIMARY KEY)");
      SQLiteNative.exec(conn.arena(), conn.db(), "INSERT INTO t VALUES (42)");
      assertEquals(42L, SQLiteNative.lastInsertRowid(conn.db()));
    }
  }

  @Test
  void busyTimeout() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.busyTimeout(conn.db(), 1000);
    }
  }

  @Test
  void statementResetAndRebind() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(
          conn.arena(), conn.db(), "CREATE TABLE t (id INTEGER PRIMARY KEY, val TEXT)");

      try (var stmt = SQLiteStatementHandle.prepare(conn, "INSERT INTO t VALUES (?, ?)")) {
        assertEquals(2, SQLiteNative.bindParameterCount(stmt.stmt()));

        // First insert
        SQLiteNative.bindInt(stmt.stmt(), 1, 1);
        SQLiteNative.bindText(conn.arena(), stmt.stmt(), 2, "first");
        SQLiteNative.step(stmt.stmt());

        // Reset and rebind
        SQLiteNative.reset(stmt.stmt());
        SQLiteNative.clearBindings(stmt.stmt());
        SQLiteNative.bindInt(stmt.stmt(), 1, 2);
        SQLiteNative.bindText(conn.arena(), stmt.stmt(), 2, "second");
        SQLiteNative.step(stmt.stmt());
      }

      // Verify both rows
      try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT COUNT(*) FROM t")) {
        SQLiteNative.step(stmt.stmt());
        assertEquals(2, SQLiteNative.columnInt(stmt.stmt(), 0));
      }
    }
  }

  // ===== Zero-copy segment tests =====

  @Test
  void columnTextSegmentReturnsRawUtf8() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE t (val TEXT)");
      SQLiteNative.exec(conn.arena(), conn.db(), "INSERT INTO t VALUES ('hello')");

      try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT val FROM t")) {
        SQLiteNative.step(stmt.stmt());

        MemorySegment seg = SQLiteNative.columnTextSegment(stmt.stmt(), 0);
        assertEquals(5, seg.byteSize());

        // Verify raw UTF-8 bytes match "hello"
        byte[] expected = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertArrayEquals(expected, actual);

        // Verify it matches the String variant
        assertEquals("hello", SQLiteNative.columnText(stmt.stmt(), 0));
      }
    }
  }

  @Test
  void columnTextSegmentNullReturnsNullSegment() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE t (val TEXT)");
      SQLiteNative.exec(conn.arena(), conn.db(), "INSERT INTO t VALUES (NULL)");

      try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT val FROM t")) {
        SQLiteNative.step(stmt.stmt());
        MemorySegment seg = SQLiteNative.columnTextSegment(stmt.stmt(), 0);
        assertEquals(MemorySegment.NULL, seg);
      }
    }
  }

  @Test
  void columnBlobSegmentReturnsRawBytes() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE t (data BLOB)");

      byte[] original = {0x01, 0x02, (byte) 0xFF, 0x00, (byte) 0xAB};
      try (var ins = SQLiteStatementHandle.prepare(conn, "INSERT INTO t VALUES (?)")) {
        SQLiteNative.bindBlob(conn.arena(), ins.stmt(), 1, original);
        SQLiteNative.step(ins.stmt());
      }

      try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT data FROM t")) {
        SQLiteNative.step(stmt.stmt());

        MemorySegment seg = SQLiteNative.columnBlobSegment(stmt.stmt(), 0);
        assertEquals(original.length, seg.byteSize());

        byte[] actual = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertArrayEquals(original, actual);

        // Verify it matches the byte[] variant
        assertArrayEquals(original, SQLiteNative.columnBlob(stmt.stmt(), 0));
      }
    }
  }

  @Test
  void columnBlobSegmentNullReturnsNullSegment() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE t (data BLOB)");
      SQLiteNative.exec(conn.arena(), conn.db(), "INSERT INTO t VALUES (NULL)");

      try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT data FROM t")) {
        SQLiteNative.step(stmt.stmt());
        MemorySegment seg = SQLiteNative.columnBlobSegment(stmt.stmt(), 0);
        assertEquals(MemorySegment.NULL, seg);
      }
    }
  }

  @Test
  void columnTextSegmentMultibyteUtf8() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE t (val TEXT)");

      String unicode = "héllo wörld 日本語";
      try (var ins = SQLiteStatementHandle.prepare(conn, "INSERT INTO t VALUES (?)")) {
        SQLiteNative.bindText(conn.arena(), ins.stmt(), 1, unicode);
        SQLiteNative.step(ins.stmt());
      }

      try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT val FROM t")) {
        SQLiteNative.step(stmt.stmt());

        MemorySegment seg = SQLiteNative.columnTextSegment(stmt.stmt(), 0);
        byte[] expectedBytes = unicode.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expectedBytes.length, seg.byteSize());

        byte[] actual = seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertArrayEquals(expectedBytes, actual);

        // Verify String round-trip
        assertEquals(unicode, SQLiteNative.columnText(stmt.stmt(), 0));
      }
    }
  }

  // ===== Error path tests =====

  @Test
  void openNonExistentReadonlyDatabaseThrows() {
    SQLeichtException ex =
        assertThrows(
            SQLeichtException.class,
            () ->
                SQLiteConnectionHandle.open(
                    "/nonexistent/path/db.sqlite", SQLiteOpenFlag.READONLY, 64));
    assertEquals(SQLiteResultCode.CANTOPEN, ex.resultCode());
  }

  @Test
  void prepareInvalidSqlThrows() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLeichtException ex =
          assertThrows(
              SQLeichtException.class,
              () -> SQLiteStatementHandle.prepare(conn, "NOT VALID SQL AT ALL"));
      assertEquals(SQLiteResultCode.ERROR, ex.resultCode());
      assertTrue(ex.getMessage().contains("ERROR"));
    }
  }

  @Test
  void bindOutOfRangeIndexThrows() throws SQLeichtException {
    try (var conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64)) {
      SQLiteNative.exec(conn.arena(), conn.db(), "CREATE TABLE t (x INTEGER)");
      try (var stmt = SQLiteStatementHandle.prepare(conn, "INSERT INTO t VALUES (?)")) {
        SQLeichtException ex =
            assertThrows(SQLeichtException.class, () -> SQLiteNative.bindInt(stmt.stmt(), 99, 1));
        assertEquals(SQLiteResultCode.RANGE, ex.resultCode());
      }
    }
  }
}
