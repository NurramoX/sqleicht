package io.sqleicht.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionExecutorTest {

  @TempDir Path tmpDir;

  @Test
  void taskRunsOnCallingThread() throws SQLeichtException {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1))) {
      Thread taskThread = db.submit(conn -> Thread.currentThread());
      assertEquals(Thread.currentThread(), taskThread);
    }
  }

  @Test
  void checkedExceptionPropagates() {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1))) {
      assertThrows(
          SQLeichtException.class,
          () ->
              db.submit(
                  conn -> {
                    throw new SQLeichtException(1, 1, "test error");
                  }));
    }
  }

  @Test
  void runtimeExceptionPropagates() {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              db.submit(
                  conn -> {
                    throw new IllegalArgumentException("boom");
                  }));
    }
  }

  @Test
  void configurableThreadCount() throws SQLeichtException {
    for (int n : new int[] {1, 2, 4}) {
      try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(n))) {
        assertEquals(n, db.threadCount());
      }
    }
  }

  @Test
  void shutdownRejectsNewSubmissions() {
    var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1));
    db.close();
    assertThrows(IllegalStateException.class, () -> db.submit(conn -> null));
  }

  @Test
  void semaphoreBackpressure() throws Exception {
    try (var db =
        SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1).connectionTimeoutMs(500))) {
      // Submit a slow task that holds the single slot
      var latch = new java.util.concurrent.CountDownLatch(1);
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  db.submit(
                      conn -> {
                        try {
                          latch.await();
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return null;
                      });
                } catch (Exception e) {
                  // expected
                }
              });

      // Give it time to acquire the slot
      Thread.sleep(100);

      // Second submit should timeout because the single slot is held
      assertThrows(SQLeichtException.class, () -> db.submit(conn -> null));

      latch.countDown();
    }
  }

  // === Deadline propagation tests ===

  private String tempDb() {
    return tmpDir.resolve("test.db").toString();
  }

  @Test
  void busyHandlerRespectsOperationDeadline() throws Exception {
    // connectionTimeoutMs=2s is the overall deadline; busyTimeoutMs=30s is intentionally much
    // longer. If deadline propagation works, the operation fails in ~2s, not 30s.
    var config =
        new SQLeichtConfig()
            .threadCount(2)
            .connectionTimeoutMs(2_000)
            .busyTimeoutMs(30_000)
            .idleTimeoutMs(0);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");

      var writerReady = new CountDownLatch(1);
      var holdLock = new CountDownLatch(1);

      // Hold a write lock from connection 1
      var lockHolder =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      db.transaction(
                          tx -> {
                            tx.update("INSERT INTO t VALUES (?)", 1);
                            writerReady.countDown();
                            try {
                              holdLock.await();
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          });
                    } catch (SQLeichtException e) {
                      // may happen during cleanup
                    }
                  });

      assertTrue(writerReady.await(5, TimeUnit.SECONDS), "Lock holder should have started");

      // Connection 2 tries to write → SQLITE_BUSY → busy handler checks deadline
      long startNanos = System.nanoTime();
      var ex =
          assertThrows(SQLeichtException.class, () -> db.update("INSERT INTO t VALUES (?)", 2));
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

      assertEquals(SQLiteResultCode.BUSY, ex.resultCode());

      // Lower bound: handler must have actually retried for ~2s, not bailed immediately
      assertTrue(
          elapsedMs >= 1_500,
          "Busy handler gave up too early — expected retries until ~2s, but took only "
              + elapsedMs
              + "ms");
      // Upper bound: must respect connectionTimeoutMs (2s), not busyTimeoutMs (30s)
      assertTrue(
          elapsedMs < 5_000,
          "Busy handler ignored deadline — expected ~2s (connectionTimeoutMs), but took "
              + elapsedMs
              + "ms");

      holdLock.countDown();
      lockHolder.join(5_000);
    }
  }

  @Test
  void busyTimeoutStillCapsIndependently() throws Exception {
    // busyTimeoutMs=1s is shorter than connectionTimeoutMs=30s.
    // The busy handler should stop after 1s even though the deadline is 30s away.
    var config =
        new SQLeichtConfig()
            .threadCount(2)
            .connectionTimeoutMs(30_000)
            .busyTimeoutMs(1_000)
            .idleTimeoutMs(0);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");

      var writerReady = new CountDownLatch(1);
      var holdLock = new CountDownLatch(1);

      var lockHolder =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      db.transaction(
                          tx -> {
                            tx.update("INSERT INTO t VALUES (?)", 1);
                            writerReady.countDown();
                            try {
                              holdLock.await();
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          });
                    } catch (SQLeichtException e) {
                      // may happen during cleanup
                    }
                  });

      assertTrue(writerReady.await(5, TimeUnit.SECONDS));

      long startNanos = System.nanoTime();
      var ex =
          assertThrows(SQLeichtException.class, () -> db.update("INSERT INTO t VALUES (?)", 2));
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

      assertEquals(SQLiteResultCode.BUSY, ex.resultCode());

      // Lower bound: handler must have actually retried for ~1s, not bailed immediately
      assertTrue(
          elapsedMs >= 500,
          "Busy handler gave up too early — expected retries until ~1s, but took only "
              + elapsedMs
              + "ms");
      // Upper bound: must respect busyTimeoutMs (1s), not connectionTimeoutMs (30s)
      assertTrue(
          elapsedMs < 5_000,
          "Busy handler ignored busyTimeoutMs — expected ~1s, but took " + elapsedMs + "ms");

      holdLock.countDown();
      lockHolder.join(5_000);
    }
  }
}
