package io.sqleicht;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.core.SQLeichtException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SQLeichtTest {

  private SQLeicht db;

  @BeforeEach
  void setUp() {
    db = SQLeicht.create(":memory:");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void executeCreateTable() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    try (var rows = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='t'")) {
      assertEquals(1, rows.size());
      assertEquals("t", rows.get(0).getText(0));
    }
  }

  @Test
  void executePragma() throws SQLeichtException {
    try (var rows = db.query("PRAGMA journal_mode")) {
      assertEquals(1, rows.size());
      // In-memory shared cache databases report "memory" instead of "wal"
      String mode = rows.get(0).getText(0);
      assertTrue("wal".equals(mode) || "memory".equals(mode), "Unexpected journal mode: " + mode);
    }
  }

  @Test
  void updateInsertReturnsRowCount() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    long count = db.update("INSERT INTO t VALUES (?, ?)", 1, "hello");
    assertEquals(1L, count);
  }

  @Test
  void updateModifyReturnsRowCount() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    db.update("INSERT INTO t VALUES (?, ?)", 1, "hello");
    db.update("INSERT INTO t VALUES (?, ?)", 2, "world");
    long count = db.update("UPDATE t SET name = ? WHERE id > ?", "updated", 0);
    assertEquals(2L, count);
  }

  @Test
  void queryWithParams() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT, score REAL)");
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "alice", 9.5);
    db.update("INSERT INTO t VALUES (?, ?, ?)", 2, "bob", 7.3);

    try (var rows = db.query("SELECT * FROM t WHERE score > ?", 8.0)) {
      assertEquals(1, rows.size());
      var row = rows.get(0);
      assertEquals(1, row.getInt(0));
      assertEquals("alice", row.getText(1));
      assertEquals(9.5, row.getDouble(2), 0.001);
    }
  }

  @Test
  void queryEmptyResult() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER)");
    try (var rows = db.query("SELECT * FROM t")) {
      assertTrue(rows.isEmpty());
      assertEquals(0, rows.size());
    }
  }

  @Test
  void queryNullValues() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    db.update("INSERT INTO t VALUES (?, ?)", 1, (Object) null);

    try (var rows = db.query("SELECT * FROM t")) {
      assertEquals(1, rows.size());
      var row = rows.get(0);
      assertEquals(1, row.getInt(0));
      assertTrue(row.isNull(1));
      assertEquals(null, row.getText(1));
    }
  }

  @Test
  void prepareBindExecuteUpdate() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    long count =
        db.prepare("INSERT INTO t VALUES (?, ?)").bind(1, 1).bind(2, "hello").executeUpdate();
    assertEquals(1L, count);

    try (var rows = db.query("SELECT * FROM t")) {
      assertEquals(1, rows.size());
      assertEquals("hello", rows.get(0).getText(1));
    }
  }

  @Test
  void prepareBindQuery() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    db.update("INSERT INTO t VALUES (?, ?)", 1, "hello");
    db.update("INSERT INTO t VALUES (?, ?)", 2, "world");

    try (var rows = db.prepare("SELECT * FROM t WHERE id = ?").bind(1, 2).query()) {
      assertEquals(1, rows.size());
      assertEquals("world", rows.get(0).getText(1));
    }
  }

  @Test
  void prepareResetRebind() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    var stmt = db.prepare("INSERT INTO t VALUES (?, ?)");

    stmt.bind(1, 1).bind(2, "first").executeUpdate();
    stmt.reset().bind(1, 2).bind(2, "second").executeUpdate();

    try (var rows = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(2, rows.get(0).getInt(0));
    }
  }

  @Test
  void autoTypeResolution() throws SQLeichtException {
    db.execute("CREATE TABLE t (a INTEGER, b INTEGER, c REAL, d TEXT, e BLOB)");
    byte[] blob = {0x01, 0x02, 0x03};
    db.update("INSERT INTO t VALUES (?, ?, ?, ?, ?)", 42, 100L, 3.14, "text", blob);

    try (var rows = db.query("SELECT * FROM t")) {
      var row = rows.get(0);
      assertEquals(42, row.getInt(0));
      assertEquals(100L, row.getLong(1));
      assertEquals(3.14, row.getDouble(2), 0.001);
      assertEquals("text", row.getText(3));
      assertArrayEquals(blob, row.getBlob(4));
    }
  }

  @Test
  void lastInsertRowid() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.update("INSERT INTO t VALUES (NULL, ?)", "hello");
    // Note: lastInsertRowid may run on a different connection slot,
    // so we use submit to ensure same connection
    long rowid =
        db.submit(
            conn -> {
              conn.execute("CREATE TABLE t2 (id INTEGER PRIMARY KEY, name TEXT)");
              try (var stmt = conn.prepare("INSERT INTO t2 VALUES (NULL, 'test')")) {
                stmt.step();
              }
              return conn.lastInsertRowid();
            });
    assertEquals(1, rowid);
  }

  @Test
  void columnAccessByName() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT, score REAL)");
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "alice", 9.5);

    try (var rows = db.query("SELECT * FROM t")) {
      var row = rows.get(0);
      assertEquals(1, row.getInt("id"));
      assertEquals("alice", row.getText("name"));
      assertEquals(9.5, row.getDouble("score"), 0.001);
    }
  }

  @Test
  void columnNames() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT, score REAL)");
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "alice", 9.5);

    try (var rows = db.query("SELECT * FROM t")) {
      assertEquals(3, rows.columnCount());
      var names = rows.columnNames();
      assertEquals("id", names.get(0));
      assertEquals("name", names.get(1));
      assertEquals("score", names.get(2));
    }
  }

  @Test
  void submitEscapeHatch() throws SQLeichtException {
    String result =
        db.submit(
            conn -> {
              conn.execute("CREATE TABLE t (val TEXT)");
              try (var stmt = conn.prepare("INSERT INTO t VALUES (?)")) {
                stmt.bindText(1, "hello from submit");
                stmt.step();
              }
              try (var stmt = conn.prepare("SELECT val FROM t")) {
                stmt.step();
                return stmt.columnText(0);
              }
            });
    assertEquals("hello from submit", result);
  }

  @Test
  void blobRoundTrip() throws SQLeichtException {
    db.execute("CREATE TABLE t (data BLOB)");
    byte[] original = new byte[1024];
    for (int i = 0; i < original.length; i++) {
      original[i] = (byte) (i % 256);
    }

    db.update("INSERT INTO t VALUES (?)", (Object) original);

    try (var rows = db.query("SELECT data FROM t")) {
      assertArrayEquals(original, rows.get(0).getBlob(0));
    }
  }

  @Test
  void localDateRoundTrip() throws SQLeichtException {
    db.execute("CREATE TABLE t (d TEXT)");
    LocalDate date = LocalDate.of(2026, 3, 9);
    db.update("INSERT INTO t VALUES (?)", date);

    try (var rows = db.query("SELECT d FROM t")) {
      assertEquals("2026-03-09", rows.get(0).getText(0));
      assertEquals(date, rows.get(0).getLocalDate(0));
      assertEquals(date, rows.get(0).getLocalDate("d"));
    }
  }

  @Test
  void localTimeRoundTrip() throws SQLeichtException {
    db.execute("CREATE TABLE t (t TEXT)");
    LocalTime time = LocalTime.of(14, 30, 15);
    db.update("INSERT INTO t VALUES (?)", time);

    try (var rows = db.query("SELECT t FROM t")) {
      assertEquals("14:30:15", rows.get(0).getText(0));
      assertEquals(time, rows.get(0).getLocalTime(0));
    }
  }

  @Test
  void localDateTimeRoundTrip() throws SQLeichtException {
    db.execute("CREATE TABLE t (dt TEXT)");
    LocalDateTime dateTime = LocalDateTime.of(2026, 3, 9, 14, 30, 15);
    db.update("INSERT INTO t VALUES (?)", dateTime);

    try (var rows = db.query("SELECT dt FROM t")) {
      assertEquals("2026-03-09T14:30:15", rows.get(0).getText(0));
      assertEquals(dateTime, rows.get(0).getLocalDateTime(0));
      assertEquals(dateTime, rows.get(0).getLocalDateTime("dt"));
    }
  }

  @Test
  void instantRoundTrip() throws SQLeichtException {
    db.execute("CREATE TABLE t (ts TEXT)");
    Instant instant = Instant.parse("2026-03-09T14:30:15Z");
    db.update("INSERT INTO t VALUES (?)", instant);

    try (var rows = db.query("SELECT ts FROM t")) {
      assertEquals(instant, rows.get(0).getInstant(0));
    }
  }

  @Test
  void offsetDateTimeRoundTrip() throws SQLeichtException {
    db.execute("CREATE TABLE t (odt TEXT)");
    OffsetDateTime odt = OffsetDateTime.of(2026, 3, 9, 14, 30, 15, 0, ZoneOffset.ofHours(2));
    db.update("INSERT INTO t VALUES (?)", odt);

    try (var rows = db.query("SELECT odt FROM t")) {
      assertEquals(odt, rows.get(0).getOffsetDateTime(0));
    }
  }

  @Test
  void zonedDateTimeStoredAsOffset() throws SQLeichtException {
    db.execute("CREATE TABLE t (zdt TEXT)");
    ZonedDateTime zdt = ZonedDateTime.of(2026, 3, 9, 14, 30, 15, 0, ZoneId.of("Europe/Berlin"));
    db.update("INSERT INTO t VALUES (?)", zdt);

    try (var rows = db.query("SELECT zdt FROM t")) {
      // Stored as OffsetDateTime — zone name is not preserved
      OffsetDateTime expected = zdt.toOffsetDateTime();
      assertEquals(expected, rows.get(0).getOffsetDateTime(0));
    }
  }

  @Test
  void temporalNullHandling() throws SQLeichtException {
    db.execute("CREATE TABLE t (d TEXT, dt TEXT)");
    db.update("INSERT INTO t VALUES (?, ?)", (Object) null, (Object) null);

    try (var rows = db.query("SELECT d, dt FROM t")) {
      assertTrue(rows.get(0).isNull(0));
      assertEquals(null, rows.get(0).getLocalDate(0));
      assertEquals(null, rows.get(0).getLocalDateTime(1));
    }
  }

  @Test
  void temporalWithPreparedStatement() throws SQLeichtException {
    db.execute("CREATE TABLE t (d TEXT, dt TEXT)");
    LocalDate date = LocalDate.of(2026, 1, 15);
    LocalDateTime dateTime = LocalDateTime.of(2026, 1, 15, 10, 0);

    db.prepare("INSERT INTO t VALUES (?, ?)").bind(1, date).bind(2, dateTime).executeUpdate();

    try (var rows = db.query("SELECT * FROM t")) {
      assertEquals(date, rows.get(0).getLocalDate(0));
      assertEquals(dateTime, rows.get(0).getLocalDateTime(1));
    }
  }

  @Test
  void sqliteDateFunctionsWorkWithIsoFormat() throws SQLeichtException {
    db.execute("CREATE TABLE events (name TEXT, date TEXT)");
    db.update("INSERT INTO events VALUES (?, ?)", "a", LocalDate.of(2026, 1, 1));
    db.update("INSERT INTO events VALUES (?, ?)", "b", LocalDate.of(2026, 6, 15));
    db.update("INSERT INTO events VALUES (?, ?)", "c", LocalDate.of(2026, 12, 31));

    try (var rows =
        db.query("SELECT name FROM events WHERE date > ? ORDER BY date", "2026-03-01")) {
      assertEquals(2, rows.size());
      assertEquals("b", rows.get(0).getText(0));
      assertEquals("c", rows.get(1).getText(0));
    }
  }

  @Test
  void transactionCommitsOnSuccess() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

    int count =
        db.transaction(
            tx -> {
              tx.update("INSERT INTO t VALUES (?, ?)", 1, "one");
              tx.update("INSERT INTO t VALUES (?, ?)", 2, "two");
              tx.update("INSERT INTO t VALUES (?, ?)", 3, "three");
              try (var rows = tx.query("SELECT COUNT(*) FROM t")) {
                return rows.get(0).getInt(0);
              }
            });

    assertEquals(3, count);

    try (var rows = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(3, rows.get(0).getInt(0));
    }
  }

  @Test
  void transactionRollsBackOnException() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.update("INSERT INTO t VALUES (?, ?)", 1, "existing");

    try {
      db.transaction(
          (TransactionFunction<Void>)
              tx -> {
                tx.update("INSERT INTO t VALUES (?, ?)", 2, "new");
                throw new SQLeichtException(0, 0, "something went wrong");
              });
    } catch (SQLeichtException e) {
      assertEquals("something went wrong", e.getMessage());
    }

    // Row 2 should not exist — rolled back
    try (var rows = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(1, rows.get(0).getInt(0));
    }
  }

  @Test
  void transactionVoidOverload() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER)");

    db.transaction(
        tx -> {
          tx.update("INSERT INTO t VALUES (?)", 1);
          tx.update("INSERT INTO t VALUES (?)", 2);
          return null;
        });

    try (var rows = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(2, rows.get(0).getInt(0));
    }
  }

  @Test
  void transactionLastInsertRowid() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");

    long rowid =
        db.transaction(
            tx -> {
              tx.update("INSERT INTO t VALUES (NULL, ?)", "first");
              tx.update("INSERT INTO t VALUES (NULL, ?)", "second");
              return tx.lastInsertRowid();
            });

    assertEquals(2, rowid);
  }

  @Test
  void batchInsert() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      rows.add(new Object[] {i, "row-" + i});
    }

    long changes = db.batch("INSERT INTO t VALUES (?, ?)", rows);
    assertEquals(1000, changes);

    try (var result = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(1000, result.get(0).getInt(0));
    }
  }

  @Test
  void batchInsideTransaction() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

    db.transaction(
        tx -> {
          tx.update("INSERT INTO t VALUES (?, ?)", 0, "before-batch");

          List<Object[]> rows = new ArrayList<>();
          for (int i = 1; i <= 100; i++) {
            rows.add(new Object[] {i, "batch-" + i});
          }
          tx.batch("INSERT INTO t VALUES (?, ?)", rows);

          tx.update("INSERT INTO t VALUES (?, ?)", 101, "after-batch");
          return null;
        });

    try (var result = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(102, result.get(0).getInt(0));
    }
  }

  @Test
  void batchRollsBackOnError() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
    db.update("INSERT INTO t VALUES (?, ?)", 1, "existing");

    List<Object[]> rows =
        List.of(
            new Object[] {2, "ok"},
            new Object[] {1, "duplicate-pk"},
            new Object[] {3, "never-reached"});

    try {
      db.batch("INSERT INTO t VALUES (?, ?)", rows);
    } catch (SQLeichtException e) {
      // expected — unique constraint violation
    }

    // Only the original row should exist — batch was in a transaction
    try (var result = db.query("SELECT COUNT(*) FROM t")) {
      assertEquals(1, result.get(0).getInt(0));
    }
  }

  @Test
  void forEachStreamsRows() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    for (int i = 0; i < 100; i++) {
      db.update("INSERT INTO t VALUES (?, ?)", i, "row-" + i);
    }

    AtomicInteger count = new AtomicInteger();
    AtomicLong idSum = new AtomicLong();

    db.forEach(
        "SELECT id, name FROM t ORDER BY id",
        row -> {
          count.incrementAndGet();
          idSum.addAndGet(row.getInt("id"));
          assertNotNull(row.getText("name"));
        });

    assertEquals(100, count.get());
    assertEquals(4950, idSum.get()); // sum of 0..99
  }

  @Test
  void forEachWithParams() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
    for (int i = 0; i < 50; i++) {
      db.update("INSERT INTO t VALUES (?, ?)", i, "row-" + i);
    }

    AtomicInteger count = new AtomicInteger();
    db.forEach("SELECT * FROM t WHERE id >= ?", row -> count.incrementAndGet(), 25);

    assertEquals(25, count.get());
  }

  @Test
  void forEachEmptyResult() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER)");

    AtomicInteger count = new AtomicInteger();
    db.forEach("SELECT * FROM t", row -> count.incrementAndGet());

    assertEquals(0, count.get());
  }

  @Test
  void forEachAllTypes() throws SQLeichtException {
    db.execute("CREATE TABLE t (i INTEGER, r REAL, t TEXT, b BLOB)");
    byte[] blob = {1, 2, 3};
    db.update("INSERT INTO t VALUES (?, ?, ?, ?)", 42, 3.14, "hello", blob);

    db.forEach(
        "SELECT * FROM t",
        row -> {
          assertEquals(42, row.getInt(0));
          assertEquals(3.14, row.getDouble(1), 0.001);
          assertEquals("hello", row.getText(2));
          assertArrayEquals(blob, row.getBlob(3));
        });
  }

  @Test
  void forEachStreamsWithoutAccumulating() throws SQLeichtException {
    // With -Xmx64m on the test JVM, accumulating 100K × 1KB = ~100MB would OOM.
    // True streaming processes one row at a time — peak memory stays bounded.
    db.execute("CREATE TABLE big (id INTEGER, payload TEXT)");
    String kb = "x".repeat(1024);

    db.transaction(
        tx -> {
          List<Object[]> batch = new ArrayList<>();
          for (int i = 0; i < 100_000; i++) {
            batch.add(new Object[] {i, kb});
          }
          tx.batch("INSERT INTO big VALUES (?, ?)", batch);
          return null;
        });

    AtomicInteger count = new AtomicInteger();

    db.forEach(
        "SELECT id, payload FROM big",
        row -> {
          row.getText("payload"); // force materialization
          count.incrementAndGet();
        });

    assertEquals(100_000, count.get());
  }

  @Test
  void forEachZeroCopySegmentAccess() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, data TEXT, blob_data BLOB)");
    byte[] blob = {1, 2, 3, 4, 5};
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "hello", blob);

    db.forEach(
        "SELECT * FROM t",
        row -> {
          // Zero-copy: direct segment access — no String/byte[] construction
          MemorySegment textSeg = row.getSegment("data");
          assertNotEquals(MemorySegment.NULL, textSeg);
          assertEquals(5, textSeg.byteSize()); // "hello" = 5 bytes UTF-8

          MemorySegment blobSeg = row.getSegment("blob_data");
          assertNotEquals(MemorySegment.NULL, blobSeg);
          assertEquals(5, blobSeg.byteSize());

          // Convenience methods still work (materialize from segment)
          assertEquals("hello", row.getText("data"));
          assertArrayEquals(blob, row.getBlob("blob_data"));
        });
  }

  @Test
  void forEachInTransaction() throws SQLeichtException {
    db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

    db.transaction(
        tx -> {
          for (int i = 0; i < 10; i++) {
            tx.update("INSERT INTO t VALUES (?, ?)", i, "row-" + i);
          }

          AtomicInteger count = new AtomicInteger();
          tx.forEach("SELECT * FROM t", row -> count.incrementAndGet());
          assertEquals(10, count.get());
          return null;
        });
  }

  @Test
  void zeroColumnSegment() throws SQLeichtException {
    db.execute("CREATE TABLE t (name TEXT)");
    db.update("INSERT INTO t VALUES (?)", "hello");

    db.forEach(
        "SELECT name FROM t",
        row -> {
          MemorySegment seg = row.getSegment(0);
          assertNotNull(seg);
          byte[] expected = "hello".getBytes(StandardCharsets.UTF_8);
          byte[] actual = seg.toArray(ValueLayout.JAVA_BYTE);
          assertArrayEquals(expected, actual);
        });
  }
}
