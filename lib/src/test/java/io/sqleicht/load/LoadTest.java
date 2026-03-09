package io.sqleicht.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadTest {

  @TempDir Path tempDir;

  @Test
  void workersExecuteSimultaneously() throws Exception {
    Path dbFile = tempDir.resolve("concurrent.db");

    try (var db = SQLeicht.create(dbFile.toString(), new SQLeichtConfig().threadCount(2))) {
      // Both tasks must be running at the same time for this to complete.
      // If workers are sequential, the second task can't start until the first finishes,
      // and the first is blocked waiting for the second — deadlock, timeout.
      CountDownLatch bothRunning = new CountDownLatch(2);

      Thread t1 =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      db.submit(
                          conn -> {
                            bothRunning.countDown();
                            try {
                              if (!bothRunning.await(5, TimeUnit.SECONDS)) {
                                throw new RuntimeException("Not truly concurrent");
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                            return null;
                          });
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  });

      Thread t2 =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      db.submit(
                          conn -> {
                            bothRunning.countDown();
                            try {
                              if (!bothRunning.await(5, TimeUnit.SECONDS)) {
                                throw new RuntimeException("Not truly concurrent");
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                            return null;
                          });
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  });

      t1.join(10_000);
      t2.join(10_000);

      assertFalse(t1.isAlive(), "Task 1 should have completed");
      assertFalse(t2.isAlive(), "Task 2 should have completed");
    }
  }

  @Test
  void thousandVirtualThreadsOnFileDatabase() throws Exception {
    Path dbFile = tempDir.resolve("load.db");

    try (var db = SQLeicht.create(dbFile.toString(), new SQLeichtConfig().threadCount(2))) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");

      try (var rows = db.query("PRAGMA journal_mode")) {
        assertEquals("wal", rows.get(0).getText(0));
      }

      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errors = new AtomicInteger();
      Set<Thread> ffmThreads = ConcurrentHashMap.newKeySet();

      for (int i = 0; i < taskCount; i++) {
        final int id = i;
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    db.update("INSERT INTO t VALUES (?, ?)", id, "task-" + id);
                    db.submit(
                        conn -> {
                          ffmThreads.add(Thread.currentThread());
                          return null;
                        });
                  } catch (Throwable e) {
                    errors.incrementAndGet();
                  } finally {
                    latch.countDown();
                  }
                });
      }

      latch.await();

      assertEquals(0, errors.get(), "Some tasks failed");

      // With direct execution, tasks run on the calling virtual threads
      assertTrue(ffmThreads.size() > 1, "Multiple virtual threads should have handled tasks");

      try (var rows = db.query("SELECT COUNT(*) FROM t")) {
        assertEquals(taskCount, rows.get(0).getInt(0));
      }
    }
  }

  @Test
  void concurrentWritersOnFileDatabase() throws Exception {
    Path dbFile = tempDir.resolve("writers.db");

    try (var db = SQLeicht.create(dbFile.toString(), new SQLeichtConfig().threadCount(2))) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val TEXT)");

      int writerCount = 500;
      CountDownLatch latch = new CountDownLatch(writerCount);
      AtomicInteger errors = new AtomicInteger();

      for (int i = 0; i < writerCount; i++) {
        final int id = i;
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    db.submit(
                        conn -> {
                          conn.execute("INSERT INTO t VALUES (" + id + ", 'writer-" + id + "')");
                          return null;
                        });
                  } catch (Throwable e) {
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
  void concurrentReadsAndWrites() throws Exception {
    Path dbFile = tempDir.resolve("readwrite.db");

    try (var db = SQLeicht.create(dbFile.toString(), new SQLeichtConfig().threadCount(2))) {
      db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, val INTEGER)");

      for (int i = 0; i < 100; i++) {
        db.update("INSERT INTO t VALUES (?, ?)", i, i * 10);
      }

      int totalTasks = 300;
      CountDownLatch latch = new CountDownLatch(totalTasks);
      AtomicInteger readErrors = new AtomicInteger();
      AtomicInteger writeErrors = new AtomicInteger();

      for (int i = 0; i < totalTasks; i++) {
        final int id = i;
        boolean isReader = (i % 3 != 0);
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    if (isReader) {
                      try (var rows = db.query("SELECT SUM(val) FROM t")) {
                        assertTrue(rows.size() > 0);
                      }
                    } else {
                      db.update("INSERT OR REPLACE INTO t VALUES (?, ?)", id + 1000, id);
                    }
                  } catch (Throwable e) {
                    if (isReader) readErrors.incrementAndGet();
                    else writeErrors.incrementAndGet();
                  } finally {
                    latch.countDown();
                  }
                });
      }

      latch.await();

      assertEquals(0, readErrors.get(), "Some reads failed");
      assertEquals(0, writeErrors.get(), "Some writes failed");
    }
  }
}
