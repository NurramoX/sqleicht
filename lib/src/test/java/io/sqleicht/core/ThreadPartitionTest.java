package io.sqleicht.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.SQLeicht;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class ThreadPartitionTest {

  @Test
  void allTasksRunOnPlatformThreads() throws SQLeichtException {
    try (var db = SQLeicht.create(":memory:")) {
      db.execute("CREATE TABLE t (id INTEGER)");
      for (int i = 0; i < 20; i++) {
        Thread taskThread =
            db.submit(
                conn -> {
                  return Thread.currentThread();
                });
        assertTrue(!taskThread.isVirtual(), "Task ran on virtual thread");
        assertTrue(
            taskThread.getName().startsWith("sqleicht-ffi-"),
            "Thread name was: " + taskThread.getName());
      }
    }
  }

  @Test
  void distinctThreadCountEqualsConfigured() throws SQLeichtException {
    int threadCount = 3;
    var config = new io.sqleicht.SQLeichtConfig().threadCount(threadCount);
    try (var db = SQLeicht.create(":memory:", config)) {
      Set<String> threadNames = ConcurrentHashMap.newKeySet();
      for (int i = 0; i < 30; i++) {
        db.submit(
            conn -> {
              threadNames.add(Thread.currentThread().getName());
              return null;
            });
      }
      assertEquals(threadCount, threadNames.size());
    }
  }

  @Test
  void callingThreadDiffersFromExecutingThread() throws Exception {
    try (var db = SQLeicht.create(":memory:")) {
      Thread callingThread = Thread.currentThread();
      Thread executingThread =
          db.submit(
              conn -> {
                return Thread.currentThread();
              });
      assertNotEquals(callingThread, executingThread);
    }
  }

  @Test
  void partitionHoldsThroughClientApi() throws SQLeichtException {
    try (var db = SQLeicht.create(":memory:")) {
      db.execute("CREATE TABLE t (id INTEGER, name TEXT)");
      db.update("INSERT INTO t VALUES (?, ?)", 1, "test");

      // All these go through the client → executor → platform thread path
      Thread t1 =
          db.submit(
              conn -> {
                return Thread.currentThread();
              });
      Thread t2 =
          db.submit(
              conn -> {
                return Thread.currentThread();
              });

      assertTrue(!t1.isVirtual());
      assertTrue(!t2.isVirtual());
      assertTrue(t1.getName().startsWith("sqleicht-ffi-"));
      assertTrue(t2.getName().startsWith("sqleicht-ffi-"));
    }
  }
}
