package io.sqleicht.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import org.junit.jupiter.api.Test;

class ConnectionExecutorTest {

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
}
