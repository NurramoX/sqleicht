package io.sqleicht;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.core.SQLeichtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BugFixTest {

  private SQLeicht db;

  @BeforeEach
  void setUp() throws SQLeichtException {
    db = SQLeicht.create(":memory:");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  // === Bug #2: step() error checking ===

  @Test
  void updateThrowsOnUniqueConstraintViolation() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    db.update("INSERT INTO t VALUES (?)", 1);
    assertThrows(SQLeichtException.class, () -> db.update("INSERT INTO t VALUES (?)", 1));
  }

  @Test
  void updateThrowsOnCheckConstraintViolation() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, val INTEGER CHECK(val > 0))");
    assertThrows(SQLeichtException.class, () -> db.update("INSERT INTO t VALUES (?, ?)", 1, -5));
  }

  @Test
  void updateThrowsOnNotNullConstraintViolation() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER NOT NULL)");
    assertThrows(
        SQLeichtException.class, () -> db.update("INSERT INTO t VALUES (?)", (Object) null));
  }

  @Test
  void batchThrowsOnConstraintViolation() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    assertThrows(
        SQLeichtException.class,
        () ->
            db.transaction(
                tx -> {
                  tx.batch(
                      "INSERT INTO t VALUES (?)",
                      java.util.List.of(new Object[] {1}, new Object[] {1}));
                  return null;
                }));
  }

  @Test
  void transactionRollsBackOnConstraintViolation() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val INTEGER CHECK(val > 0))");
    assertThrows(
        SQLeichtException.class,
        () ->
            db.transaction(
                tx -> {
                  tx.update("INSERT INTO t VALUES (?, ?)", 1, 10); // succeeds
                  tx.update("INSERT INTO t VALUES (?, ?)", 2, -5); // CHECK violation
                  return null;
                }));
    // row 1 should be rolled back too
    try (var rows = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(0, rows.get(0).getInt(0));
    }
  }

  // === Bug #3: columnTypes per row ===

  @Test
  void mixedTypeColumnReturnsCorrectTypesPerRow() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, val)");
    db.update("INSERT INTO t VALUES (?, ?)", 1, "text");
    db.update("INSERT INTO t VALUES (?, ?)", 2, 42);
    db.update("INSERT INTO t VALUES (?, ?)", 3, (Object) null);

    try (var rows = db.query("SELECT val FROM t ORDER BY id")) {
      assertEquals("text", rows.get(0).getText(0));
      assertEquals(42, rows.get(1).getInt(0));
      assertTrue(rows.get(2).isNull(0));
    }
  }

  // === Binding edge cases ===

  @Test
  void floatParameterBindsCorrectly() throws SQLeichtException {
    db.execute("CREATE TABLE t (val REAL)");
    db.update("INSERT INTO t VALUES (?)", 3.14f);
    try (var rows = db.query("SELECT val FROM t")) {
      assertEquals(3.14, rows.get(0).getDouble(0), 0.01);
    }
  }

  @Test
  void shortParameterBindsCorrectly() throws SQLeichtException {
    db.execute("CREATE TABLE t (val INTEGER)");
    db.update("INSERT INTO t VALUES (?)", (short) 42);
    try (var rows = db.query("SELECT val FROM t")) {
      assertEquals(42, rows.get(0).getInt(0));
    }
  }

  @Test
  void byteParameterBindsCorrectly() throws SQLeichtException {
    db.execute("CREATE TABLE t (val INTEGER)");
    db.update("INSERT INTO t VALUES (?)", (byte) 7);
    try (var rows = db.query("SELECT val FROM t")) {
      assertEquals(7, rows.get(0).getInt(0));
    }
  }

  // === Edge cases ===

  @Test
  void emptyStringRoundTrips() throws SQLeichtException {
    db.execute("CREATE TABLE t (val TEXT)");
    db.update("INSERT INTO t VALUES (?)", "");
    try (var rows = db.query("SELECT val FROM t")) {
      assertEquals("", rows.get(0).getText(0));
    }
  }

  @Test
  void emptyBlobRoundTrips() throws SQLeichtException {
    db.execute("CREATE TABLE t (val BLOB)");
    db.update("INSERT INTO t VALUES (?)", new byte[0]);
    try (var rows = db.query("SELECT val FROM t")) {
      assertNull(rows.get(0).getBlob(0));
    }
  }

  @Test
  void unknownColumnNameThrows() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER)");
    db.update("INSERT INTO t VALUES (?)", 1);
    try (var rows = db.query("SELECT id FROM t")) {
      assertThrows(IllegalArgumentException.class, () -> rows.get(0).getInt("nonexistent"));
    }
  }

  @Test
  void forEachConsumerThrowDoesNotLeakStatement() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER)");
    db.update("INSERT INTO t VALUES (?)", 1);

    assertThrows(
        RuntimeException.class,
        () ->
            db.forEach(
                "SELECT id FROM t",
                row -> {
                  throw new RuntimeException("oops");
                }));

    // DB should still work fine after the error
    try (var rows = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(1, rows.get(0).getInt(0));
    }
  }

  @Test
  void doubleCloseIsSafe() {
    assertDoesNotThrow(
        () -> {
          var tempDb = SQLeicht.create(":memory:");
          tempDb.close();
          tempDb.close();
        });
  }

  @Test
  void operationAfterCloseThrows() {
    var tempDb = SQLeicht.create(":memory:");
    tempDb.close();
    assertThrows(Exception.class, () -> tempDb.submit(conn -> null));
  }

  // === Config validation ===

  @Test
  void invalidThreadCountThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(0)));
  }

  @Test
  void invalidPageSizeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SQLeicht.create(":memory:", new SQLeichtConfig().pageSize(7)));
  }

  @Test
  void invalidStatementCacheSizeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SQLeicht.create(":memory:", new SQLeichtConfig().statementCacheSize(-1)));
  }

  @Test
  void sqlInjectionInJournalModeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SQLeicht.create(
                ":memory:", new SQLeichtConfig().journalMode("WAL; DROP TABLE users; --")));
  }
}
