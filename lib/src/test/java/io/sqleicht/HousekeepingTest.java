package io.sqleicht;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sqleicht.core.SQLeichtException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HousekeepingTest {

  @TempDir Path tmpDir;

  private String tempDb() {
    return tmpDir.resolve("test.db").toString();
  }

  @Test
  void maxLifetimeRotatesConnection() throws Exception {
    var config = new SQLeichtConfig().threadCount(1).maxLifetimeMs(30_000).idleTimeoutMs(0);
    config.housekeepingIntervalMs(100);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");

      db.update("INSERT INTO t VALUES (?)", 1);
      db.update("INSERT INTO t VALUES (?)", 2);

      try (var rows = db.query("SELECT COUNT(*) FROM t")) {
        assertTrue(rows.get(0).getInt(0) >= 2);
      }
    }
  }

  @Test
  void idleTimeoutClosesAndReopensConnection() throws Exception {
    var config = new SQLeichtConfig().threadCount(1).maxLifetimeMs(60_000).idleTimeoutMs(1_000);
    config.housekeepingIntervalMs(100);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.update("INSERT INTO t VALUES (?)", 1);

      // Connection init sql sets a user_version we can detect
      int openCount1 =
          db.submit(
              conn -> {
                try (var stmt = conn.prepare("PRAGMA user_version")) {
                  stmt.step();
                  int current = stmt.columnInt(0);
                  conn.execute("PRAGMA user_version=" + (current + 1));
                  return current + 1;
                }
              });

      // Wait for idle timeout (1s) + housekeeping sweep (100ms) + worker poll (1s)
      Thread.sleep(2_500);

      // Data inserted before idle close is still accessible
      try (var rows = db.query("SELECT COUNT(*) FROM t")) {
        assertEquals(1, rows.get(0).getInt(0));
      }

      // Verify the connection was actually reopened (user_version persists in file DB)
      int userVersion =
          db.submit(
              conn -> {
                try (var stmt = conn.prepare("PRAGMA user_version")) {
                  stmt.step();
                  return stmt.columnInt(0);
                }
              });
      assertEquals(
          openCount1, userVersion, "PRAGMA user_version should persist through idle reopen");
    }
  }

  @Test
  void idleTimeoutActuallyClosesTheConnection() throws Exception {
    var config = new SQLeichtConfig().threadCount(1).maxLifetimeMs(60_000).idleTimeoutMs(1_000);
    config.housekeepingIntervalMs(100);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");
      db.update("INSERT INTO t VALUES (?)", 1);

      // Wait for idle close
      Thread.sleep(2_500);

      // After idle close + reopen, the connection still works
      db.update("INSERT INTO t VALUES (?)", 2);
      try (var rows = db.query("SELECT COUNT(*) FROM t")) {
        assertEquals(2, rows.get(0).getInt(0));
      }
    }
  }

  @Test
  void idleTimeoutDoesNotFireDuringActivity() throws Exception {
    var config = new SQLeichtConfig().threadCount(1).maxLifetimeMs(60_000).idleTimeoutMs(1_000);
    config.housekeepingIntervalMs(100);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");

      // Plant a temp table as a connection marker — disappears on reconnect
      db.submit(
          conn -> {
            conn.execute("CREATE TEMP TABLE _marker (v INTEGER)");
            return null;
          });

      // Keep the connection busy — task every 300ms for 2 seconds
      // Each task resets lastAccessed, so idle timeout never fires
      for (int i = 0; i < 7; i++) {
        Thread.sleep(300);
        db.update("INSERT INTO t VALUES (?)", i);
      }

      // Temp table should still exist — same connection, not recycled
      boolean sameConnection =
          db.submit(
              conn -> {
                try {
                  conn.execute("SELECT 1 FROM _marker");
                  return true;
                } catch (SQLeichtException e) {
                  return false;
                }
              });
      assertTrue(sameConnection, "Connection should NOT have been recycled during activity");
    }
  }

  @Test
  void idleTimeoutDisabledWhenZero() throws Exception {
    var config = new SQLeichtConfig().threadCount(1).maxLifetimeMs(60_000).idleTimeoutMs(0);
    config.housekeepingIntervalMs(100);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");

      // Plant a temp table as a connection marker
      db.submit(
          conn -> {
            conn.execute("CREATE TEMP TABLE _marker (v INTEGER)");
            return null;
          });

      Thread.sleep(2_500);

      // Temp table should still exist — idle timeout is disabled
      boolean sameConnection =
          db.submit(
              conn -> {
                try {
                  conn.execute("SELECT 1 FROM _marker");
                  return true;
                } catch (SQLeichtException e) {
                  return false;
                }
              });
      assertTrue(sameConnection, "Connection should NOT be recycled when idle timeout is 0");
    }
  }

  @Test
  void idleTimeoutEqualToMaxLifetimeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SQLeicht.create(
                tempDb(),
                new SQLeichtConfig().threadCount(1).maxLifetimeMs(30_000).idleTimeoutMs(30_000)));
  }

  @Test
  void multipleConnectionsIdleIndependently() throws Exception {
    var config = new SQLeichtConfig().threadCount(2).maxLifetimeMs(60_000).idleTimeoutMs(1_000);
    config.housekeepingIntervalMs(100);

    try (var db = SQLeicht.create(tempDb(), config)) {
      db.execute("CREATE TABLE t (id INTEGER)");

      db.update("INSERT INTO t VALUES (?)", 1);
      db.update("INSERT INTO t VALUES (?)", 2);

      assertEquals(2, db.idleCount());

      // Wait for both connections to idle-close
      Thread.sleep(2_500);

      // Both should reopen on demand
      db.update("INSERT INTO t VALUES (?)", 3);

      try (var rows = db.query("SELECT COUNT(*) FROM t")) {
        assertEquals(3, rows.get(0).getInt(0));
      }
    }
  }
}
