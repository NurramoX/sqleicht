package io.sqleicht.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import org.junit.jupiter.api.Test;

class ConnectionExecutorTest {

  @Test
  void taskRunsOnPlatformThread() throws SQLeichtException {
    try (var db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1))) {
      Thread taskThread = db.submit(conn -> Thread.currentThread());
      assertTrue(taskThread.getName().startsWith("sqleicht-ffi-"));
      assertTrue(!taskThread.isVirtual());
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
      // Submit a slow task that blocks the single thread
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

      // Give it time to start
      Thread.sleep(100);

      // Second submit should timeout because the single thread is busy
      assertThrows(SQLeichtException.class, () -> db.submit(conn -> null));

      latch.countDown();
    }
  }
}
