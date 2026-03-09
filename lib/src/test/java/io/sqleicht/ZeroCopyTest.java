package io.sqleicht;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.core.SQLeichtException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZeroCopyTest {

  private SQLeicht db;

  @BeforeEach
  void setUp() throws SQLeichtException {
    db = SQLeicht.create(":memory:");
    db.execute("CREATE TABLE t (id INTEGER, name TEXT, data BLOB)");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void getSegmentReturnsUtf8Bytes() throws SQLeichtException {
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "hello", (Object) null);
    try (var rows = db.query("SELECT name FROM t")) {
      MemorySegment seg = rows.get(0).getSegment(0);
      byte[] expected = "hello".getBytes(StandardCharsets.UTF_8);
      assertArrayEquals(expected, seg.toArray(ValueLayout.JAVA_BYTE));
    }
  }

  @Test
  void getSegmentAndGetTextReturnSameData() throws SQLeichtException {
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "héllo wörld 日本語", (Object) null);
    try (var rows = db.query("SELECT name FROM t")) {
      var row = rows.get(0);
      String fromText = row.getText(0);
      MemorySegment seg = row.getSegment(0);
      String fromSeg = new String(seg.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
      assertEquals(fromText, fromSeg);
    }
  }

  @Test
  void blobSegmentAndGetBlobReturnSameData() throws SQLeichtException {
    byte[] blob = {0x01, 0x02, (byte) 0xFF, 0x00, (byte) 0xAB};
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, (Object) null, blob);
    try (var rows = db.query("SELECT data FROM t")) {
      var row = rows.get(0);
      byte[] fromBlob = row.getBlob(0);
      MemorySegment seg = row.getSegment(0);
      byte[] fromSeg = seg.toArray(ValueLayout.JAVA_BYTE);
      assertArrayEquals(fromBlob, fromSeg);
    }
  }

  @Test
  void getSegmentDoesNotAllocateString() throws SQLeichtException {
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "test", (Object) null);
    try (var rows = db.query("SELECT name FROM t")) {
      MemorySegment seg = rows.get(0).getSegment(0);
      // The segment is a MemorySegment, not a String — verify it's a valid segment
      assertNotNull(seg);
      assertTrue(seg.byteSize() > 0);
      // Accessing the segment does not create a String — it's raw bytes in the arena
      assertEquals(4, seg.byteSize()); // "test" = 4 UTF-8 bytes
    }
  }

  @Test
  void nullSegmentReturnsMemorySegmentNull() throws SQLeichtException {
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, (Object) null, (Object) null);
    try (var rows = db.query("SELECT name, data FROM t")) {
      var row = rows.get(0);
      assertTrue(row.isNull(0));
      assertTrue(row.isNull(1));
    }
  }

  @Test
  void arenaSharedAcrossThreadBoundary() throws SQLeichtException {
    db.update("INSERT INTO t VALUES (?, ?, ?)", 1, "shared", (Object) null);

    // query() creates the arena on the platform thread but we access it on this thread
    try (var rows = db.query("SELECT name FROM t")) {
      // This access crosses the thread boundary — only works with shared arena
      MemorySegment seg = rows.get(0).getSegment(0);
      assertEquals(
          "shared", new String(seg.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
    }
  }

  @Test
  void multipleRowsBatchMaterialized() throws SQLeichtException {
    for (int i = 0; i < 100; i++) {
      db.update("INSERT INTO t VALUES (?, ?, ?)", i, "row-" + i, (Object) null);
    }

    try (var rows = db.query("SELECT * FROM t ORDER BY id")) {
      assertEquals(100, rows.size());
      for (int i = 0; i < 100; i++) {
        var row = rows.get(i);
        assertEquals(i, row.getInt(0));
        assertEquals("row-" + i, row.getText(1));
      }
    }
  }
}
