package io.sqleicht.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.SQLeicht;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class VirtualThreadTest {

  @Test
  void virtualThreadsNeverRunFfm() throws Exception {
    try (var db = SQLeicht.create(":memory:")) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");

      int virtualThreadCount = 100;
      Set<Thread> ffmThreads = ConcurrentHashMap.newKeySet();
      CountDownLatch latch = new CountDownLatch(virtualThreadCount);

      for (int i = 0; i < virtualThreadCount; i++) {
        final int id = i;
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    db.update("INSERT INTO t VALUES (?, ?)", id, "vt-" + id);

                    Thread executingThread =
                        db.submit(
                            conn -> {
                              return Thread.currentThread();
                            });
                    ffmThreads.add(executingThread);
                  } catch (SQLeichtException e) {
                    throw new RuntimeException(e);
                  } finally {
                    latch.countDown();
                  }
                });
      }

      latch.await();

      // All FFM work should have been done by platform threads
      for (Thread t : ffmThreads) {
        assertTrue(!t.isVirtual(), "FFM ran on virtual thread: " + t);
        assertTrue(t.getName().startsWith("sqleicht-ffi-"), "Unexpected thread: " + t.getName());
      }

      // Should be at most threadCount distinct platform threads (default = 2)
      assertTrue(ffmThreads.size() <= 2, "More than 2 distinct FFM threads: " + ffmThreads.size());
    }
  }
}
