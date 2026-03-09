package io.sqleicht.benchmark;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Query burndown chart: isolates each layer of overhead in db.query().
 *
 * <pre>
 *   Step 1: JDBC baseline            — raw JNI, returns String
 *   Step 2: sqleicht LiveStatement   — pool + FFM prepare/bind/step/columnText (no row objects)
 *   Step 3: sqleicht query()         — full path: pool + stmt cache + row objects + HashMap
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(
    value = 1,
    jvmArgs = {"--enable-preview", "--enable-native-access=ALL-UNNAMED"})
public class QueryBurndownBenchmark {

  private static final String SQL = "SELECT val FROM reads WHERE id = ?";

  private SQLeicht db;
  private Connection jdbc;
  private Random rng;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1));
    db.execute("CREATE TABLE reads (id INTEGER PRIMARY KEY, val TEXT)");

    jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
    try (var s = jdbc.createStatement()) {
      s.execute("CREATE TABLE reads (id INTEGER PRIMARY KEY, val TEXT)");
    }

    for (int i = 0; i < 1000; i++) {
      db.update("INSERT INTO reads VALUES (?, ?)", i, "value-" + i);
      try (var ps = jdbc.prepareStatement("INSERT INTO reads VALUES (?, ?)")) {
        ps.setInt(1, i);
        ps.setString(2, "value-" + i);
        ps.executeUpdate();
      }
    }

    rng = new Random(42);
  }

  @TearDown(Level.Trial)
  public void teardown() throws Exception {
    db.close();
    jdbc.close();
  }

  // ─── Step 1: JDBC baseline ──────────────────────────────────
  // Pure JNI. prepare → bind → execute → getString → close.

  @Benchmark
  public String step1_jdbc() throws Exception {
    try (var ps = jdbc.prepareStatement(SQL)) {
      ps.setInt(1, rng.nextInt(1000));
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  // ─── Step 2: sqleicht LiveStatement ───────────────────────────
  // Pool acquire + FFM prepare/bind/step/columnText + finalize.
  // No row objects, no HashMap, no statement cache.

  @Benchmark
  public String step2_liveStatement() throws Exception {
    return db.submit(
        conn -> {
          try (var stmt = conn.prepare(SQL)) {
            stmt.bindInt(1, rng.nextInt(1000));
            stmt.step();
            return stmt.columnText(0);
          }
        });
  }

  // ─── Step 3: sqleicht query() ─────────────────────────────────
  // Full ceremony: pool + statement cache + column names + HashMap + row objects.

  @Benchmark
  public String step3_query() throws Exception {
    try (var rows = db.query(SQL, rng.nextInt(1000))) {
      return rows.get(0).getText(0);
    }
  }
}
