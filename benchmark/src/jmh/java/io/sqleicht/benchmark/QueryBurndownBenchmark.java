package io.sqleicht.benchmark;

import io.sqleicht.SQLeicht;
import io.sqleicht.SQLeichtConfig;
import io.sqleicht.core.SQLiteColumnType;
import io.sqleicht.core.SQLiteResultCode;
import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Each benchmark adds one layer of ceremony on top of the previous one, showing exactly where
 * the time goes.
 *
 * <pre>
 *   Step 1: JDBC baseline          — raw JNI, returns String
 *   Step 2: sqleicht raw submit    — semaphore + lock + FFM prepare/step/columnText
 *   Step 3: sqleicht eager query   — + column names, row objects, HashMap (simulates optimized query)
 *   Step 4: sqleicht current query — + result arena, MemorySegment copy, Cleaner, double-copy on getText
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

  // ─── Step 2: sqleicht raw submit ────────────────────────────
  // Semaphore + lock + FFM prepare/bind/step/columnText/finalize.
  // Returns String directly. No row objects, no result arena.

  @Benchmark
  public String step2_rawSubmit() throws Exception {
    return db.submit(
        conn -> {
          try (var arena = Arena.ofConfined()) {
            MemorySegment dbH = conn.db();
            MemorySegment stmt = SQLiteNative.prepare(arena, dbH, SQL);
            try {
              SQLiteNative.bindInt(stmt, 1, rng.nextInt(1000));
              SQLiteNative.step(stmt);
              return SQLiteNative.columnText(stmt, 0);
            } finally {
              SQLiteNative.finalizeStmt(stmt);
            }
          }
        });
  }

  // ─── Step 3: sqleicht eager query (optimized) ───────────────
  // Same as raw submit + column names + HashMap + SQLeichtRow objects.
  // Still returns String directly — no result arena, no double-copy.
  // This is what query() COULD look like after optimization.

  @Benchmark
  public String step3_eagerQuery() throws Exception {
    return db.submit(
        conn -> {
          try (var arena = Arena.ofConfined()) {
            MemorySegment dbH = conn.db();
            MemorySegment stmt = SQLiteNative.prepare(arena, dbH, SQL);
            try {
              SQLiteNative.bindInt(stmt, 1, rng.nextInt(1000));

              int colCount = SQLiteNative.columnCount(stmt);

              String[] columnNames = new String[colCount];
              for (int i = 0; i < colCount; i++) {
                columnNames[i] = SQLiteNative.columnName(stmt, i);
              }

              Map<String, Integer> nameIndex = new HashMap<>(colCount);
              for (int i = 0; i < colCount; i++) {
                nameIndex.put(columnNames[i], i);
              }

              List<Object[]> rows = new ArrayList<>();
              int[] columnTypes = new int[colCount];

              while (SQLiteNative.step(stmt) == SQLiteResultCode.ROW.code()) {
                Object[] values = new Object[colCount];
                for (int c = 0; c < colCount; c++) {
                  columnTypes[c] = SQLiteNative.columnType(stmt, c);
                  values[c] =
                      switch (columnTypes[c]) {
                        case SQLiteColumnType.INTEGER -> SQLiteNative.columnLong(stmt, c);
                        case SQLiteColumnType.FLOAT -> SQLiteNative.columnDouble(stmt, c);
                        case SQLiteColumnType.TEXT -> SQLiteNative.columnText(stmt, c);
                        case SQLiteColumnType.BLOB -> SQLiteNative.columnBlob(stmt, c);
                        default -> null;
                      };
                }
                rows.add(values);
              }

              return (String) rows.getFirst()[0];
            } finally {
              SQLiteNative.finalizeStmt(stmt);
            }
          }
        });
  }

  // ─── Step 4: sqleicht current query() ───────────────────────
  // The full ceremony: result arena + MemorySegment.copy + Cleaner +
  // double-copy on getText() (segment → byte[] → String).

  @Benchmark
  public String step4_currentQuery() throws Exception {
    try (var rows = db.query(SQL, rng.nextInt(1000))) {
      return rows.get(0).getText(0);
    }
  }
}
