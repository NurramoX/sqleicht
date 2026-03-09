package io.sqleicht.benchmark;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Head-to-head: sqleicht (FFM + direct execution) vs Xerial JDBC.
 *
 * <p>Both sides use a single in-memory database with identical schema and data. sqleicht is
 * configured with threadCount=1 so the benchmark measures pure per-operation overhead — semaphore +
 * lock + FFM downcall vs JDBC's JNI path.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(
    value = 1,
    jvmArgs = {"--enable-preview", "--enable-native-access=ALL-UNNAMED"})
public class ComparisonBenchmark {

  private SQLeicht db;
  private Connection jdbc;
  private int insertId;
  private Random rng;
  private List<Object[]> batchData;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    // sqleicht — single connection, direct execution on calling thread
    db = SQLeicht.create(":memory:", new SQLeichtConfig().threadCount(1));
    db.execute("CREATE TABLE writes (id INTEGER PRIMARY KEY, val TEXT)");
    db.execute("CREATE TABLE reads  (id INTEGER PRIMARY KEY, val TEXT)");
    db.execute("CREATE TABLE batch  (id INTEGER PRIMARY KEY, val TEXT)");

    // JDBC — single connection, same schema
    jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
    try (var s = jdbc.createStatement()) {
      s.execute("CREATE TABLE writes (id INTEGER PRIMARY KEY, val TEXT)");
      s.execute("CREATE TABLE reads  (id INTEGER PRIMARY KEY, val TEXT)");
      s.execute("CREATE TABLE batch  (id INTEGER PRIMARY KEY, val TEXT)");
    }

    // Seed read tables — both sides get identical data
    for (int i = 0; i < 1000; i++) {
      db.update("INSERT INTO reads VALUES (?, ?)", i, "value-" + i);
      try (var ps = jdbc.prepareStatement("INSERT INTO reads VALUES (?, ?)")) {
        ps.setInt(1, i);
        ps.setString(2, "value-" + i);
        ps.executeUpdate();
      }
    }

    insertId = 0;
    rng = new Random(42);

    batchData = new ArrayList<>(1000);
    for (int i = 0; i < 1000; i++) {
      batchData.add(new Object[] {i, "batch-" + i});
    }
  }

  @TearDown(Level.Trial)
  public void teardown() throws Exception {
    db.close();
    jdbc.close();
  }

  // ─── Single Insert ──────────────────────────────────────────

  @Benchmark
  public long sqleichtInsert() throws Exception {
    return db.update(
        "INSERT OR REPLACE INTO writes VALUES (?, ?)", insertId++ % 10_000, "value");
  }

  @Benchmark
  public int jdbcInsert() throws Exception {
    try (var ps = jdbc.prepareStatement("INSERT OR REPLACE INTO writes VALUES (?, ?)")) {
      ps.setInt(1, insertId++ % 10_000);
      ps.setString(2, "value");
      return ps.executeUpdate();
    }
  }

  // ─── Single Query by PK ────────────────────────────────────

  @Benchmark
  public String sqleichtQueryByPk() throws Exception {
    try (var rows = db.query("SELECT val FROM reads WHERE id = ?", rng.nextInt(1000))) {
      return rows.get(0).getText(0);
    }
  }

  @Benchmark
  public String jdbcQueryByPk() throws Exception {
    try (var ps = jdbc.prepareStatement("SELECT val FROM reads WHERE id = ?")) {
      ps.setInt(1, rng.nextInt(1000));
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  // ─── Batch Insert (1000 rows in transaction) ───────────────

  @Benchmark
  public long sqleichtBatch() throws Exception {
    return db.batch("INSERT OR REPLACE INTO batch VALUES (?, ?)", batchData);
  }

  @Benchmark
  public void jdbcBatch() throws Exception {
    jdbc.setAutoCommit(false);
    try (var ps = jdbc.prepareStatement("INSERT OR REPLACE INTO batch VALUES (?, ?)")) {
      for (int i = 0; i < 1000; i++) {
        ps.setInt(1, i);
        ps.setString(2, "batch-" + i);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    jdbc.commit();
    jdbc.setAutoCommit(true);
  }

  // ─── Full Table Scan (1000 rows) ───────────────────────────

  @Benchmark
  public int sqleichtScan(Blackhole bh) throws Exception {
    int[] count = {0};
    db.forEach(
        "SELECT * FROM reads",
        row -> {
          bh.consume(row.getText(1));
          count[0]++;
        });
    return count[0];
  }

  @Benchmark
  public int jdbcScan(Blackhole bh) throws Exception {
    int count = 0;
    try (var s = jdbc.createStatement();
        var rs = s.executeQuery("SELECT * FROM reads")) {
      while (rs.next()) {
        bh.consume(rs.getString(2));
        count++;
      }
    }
    return count;
  }
}
