package io.sqleicht.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RobustnessTest {

  // === 6.8 Nested submit deadlock prevention ===

  @Test
  void nestedSubmitWithOneThread() throws SQLeichtException {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1))) {
      db.execute("CREATE TABLE t (id INTEGER)");

      // Inside submit, call db.update() which internally calls submit again
      String result =
          db.submit(
              conn -> {
                db.update("INSERT INTO t VALUES (?)", 1);
                try (var rows = db.query("SELECT COUNT(*) FROM t")) {
                  return "count=" + rows.get(0).getInt(0);
                }
              });

      assertEquals("count=1", result);
    }
  }

  @Test
  void nestedSubmitWithTwoThreadsBothNesting() throws Exception {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(2))) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

      CountDownLatch latch = new CountDownLatch(2);
      AtomicInteger errors = new AtomicInteger();

      for (int i = 0; i < 2; i++) {
        final int id = i;
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    db.submit(
                        conn -> {
                          // Nested: this calls submit() from within a worker thread
                          db.update("INSERT INTO t VALUES (?, ?)", id, "nested-" + id);
                          return null;
                        });
                  } catch (SQLeichtException e) {
                    errors.incrementAndGet();
                  } finally {
                    latch.countDown();
                  }
                });
      }

      latch.await();
      assertEquals(0, errors.get());

      try (var rows = db.query("SELECT COUNT(*) FROM t")) {
        assertEquals(2, rows.get(0).getInt(0));
      }
    }
  }

  @Test
  void nestedSubmitThroughClientApi() throws SQLeichtException {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1))) {
      // submit() -> prepare().bind().executeUpdate() which calls submit() internally
      db.submit(
          conn -> {
            conn.execute("CREATE TABLE t (id INTEGER)");
            // This uses the client API which calls submit() — re-entrant
            db.update("INSERT INTO t VALUES (?)", 42);
            return null;
          });

      try (var rows = db.query("SELECT id FROM t")) {
        assertEquals(42, rows.get(0).getInt(0));
      }
    }
  }

  // === 6.9 Dead worker thread recovery ===

  @Test
  void invalidPathFailsFast() {
    // A path that cannot possibly be opened
    assertThrows(
        Exception.class,
        () -> {
          try (var db =
              SQLeicht.create(
                  "/nonexistent/path/to/db.sqlite", new SQLeichtConfig().threadCount(1))) {
            db.execute("SELECT 1");
          }
        });
  }

  // === 6.10 NOMUTEX + SHAREDCACHE safety ===

  @Test
  void inMemorySharedCacheConcurrentWriters() throws Exception {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(2))) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val TEXT)");

      int writerCount = 100;
      CountDownLatch latch = new CountDownLatch(writerCount);
      AtomicInteger errors = new AtomicInteger();

      for (int i = 0; i < writerCount; i++) {
        final int id = i;
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    db.update("INSERT INTO t VALUES (?, ?)", id, "val-" + id);
                  } catch (SQLeichtException e) {
                    errors.incrementAndGet();
                  } finally {
                    latch.countDown();
                  }
                });
      }

      latch.await();
      assertEquals(0, errors.get(), "Some writers failed");

      try (var rows = db.query("SELECT COUNT(*) FROM t")) {
        assertEquals(writerCount, rows.get(0).getInt(0));
      }
    }
  }

  @Test
  void fileDatabaseUsesNomutex() throws Exception {
    Path tmpDb = Files.createTempFile("sqleicht-nomutex-", ".db");
    try (var db = SQLeicht.create(tmpDb.toString(), new SQLeichtConfig().threadCount(2))) {
      // File-based databases use NOMUTEX — safe because each connection is serialized by a lock
      db.execute("CREATE TABLE t (id INTEGER)");
      db.update("INSERT INTO t VALUES (?)", 1);
      try (var rows = db.query("SELECT id FROM t")) {
        assertEquals(1, rows.get(0).getInt(0));
      }
    } finally {
      deleteTempDb(tmpDb);
    }
  }

  private static void deleteTempDb(Path path) {
    for (String suffix : new String[] {"-wal", "-shm", "-journal", ""}) {
      Path f = Path.of(path + suffix);
      try {
        Files.deleteIfExists(f);
      } catch (Exception e) {
        f.toFile().deleteOnExit();
      }
    }
  }

  // === 6.11 Arena leak safety ===

  @Test
  void queryResultsStoreJavaTypes() throws SQLeichtException {
    try (var db = SQLeicht.create(":memory:")) {
      db.execute("CREATE TABLE t (name TEXT)");
      db.update("INSERT INTO t VALUES (?)", "hello");

      try (var rows = db.query("SELECT name FROM t")) {
        // query() stores String directly — no arena, no segments
        assertEquals("hello", rows.get(0).getText(0));

        // getSegment() is only available on forEach rows (zero-copy)
        assertThrows(IllegalStateException.class, () -> rows.get(0).getSegment(0));
      }
    }
  }

  // === 6.12 Shutdown race prevention ===

  @Test
  void shutdownWhileTasksInFlight() throws Exception {
    var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(2));
    db.execute("CREATE TABLE t (id INTEGER)");

    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch shutdownDone = new CountDownLatch(1);
    AtomicInteger taskResult = new AtomicInteger(-1);

    // Start a slow task
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                db.submit(
                    conn -> {
                      taskStarted.countDown();
                      // Simulate work
                      try {
                        Thread.sleep(200);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      conn.execute("INSERT INTO t VALUES (1)");
                      taskResult.set(1);
                      return null;
                    });
              } catch (Exception e) {
                // may fail if shutdown beats us
              }
            });

    // Wait for task to start, then shutdown
    taskStarted.await();
    db.close();
    shutdownDone.countDown();

    // New tasks should be rejected
    assertThrows(IllegalStateException.class, () -> db.submit(conn -> null));
  }
}
