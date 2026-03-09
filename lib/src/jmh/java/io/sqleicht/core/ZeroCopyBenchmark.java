package io.sqleicht.core;

import io.sqleicht.ffi.SQLiteNative;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class ZeroCopyBenchmark {

  @Param({"100", "1000"})
  int rowCount;

  @Param({"medium", "long"})
  String textSize;

  private SQLiteConnectionHandle conn;

  @Setup(Level.Trial)
  public void setup() throws SQLeichtException {
    conn = SQLiteConnectionHandle.open(":memory:", SQLiteOpenFlag.DEFAULT, 64);

    SQLiteNative.exec(
        conn.arena(),
        conn.db(),
        "CREATE TABLE bench (id INTEGER PRIMARY KEY, name TEXT, data BLOB)");

    String text = generateText(textSize);
    byte[] blob = text.getBytes(StandardCharsets.UTF_8);

    try (var stmt = SQLiteStatementHandle.prepare(conn, "INSERT INTO bench VALUES (?, ?, ?)")) {
      for (int i = 0; i < rowCount; i++) {
        SQLiteNative.bindInt(stmt.stmt(), 1, i);
        SQLiteNative.bindText(conn.arena(), stmt.stmt(), 2, text);
        SQLiteNative.bindBlob(conn.arena(), stmt.stmt(), 3, blob);
        SQLiteNative.step(stmt.stmt());
        SQLiteNative.reset(stmt.stmt());
        SQLiteNative.clearBindings(stmt.stmt());
      }
    }
  }

  @TearDown(Level.Trial)
  public void teardown() throws Exception {
    conn.close();
  }

  /** Baseline: read text as String (copies into Java heap). */
  @Benchmark
  public void textCopy(Blackhole bh) throws SQLeichtException {
    try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT name FROM bench")) {
      while (SQLiteNative.step(stmt.stmt()) == SQLiteResultCode.ROW.code()) {
        String s = SQLiteNative.columnText(stmt.stmt(), 0);
        bh.consume(s);
      }
    }
  }

  /** Zero-copy: read text as MemorySegment (pointer into SQLite buffer). */
  @Benchmark
  public void textZeroCopy(Blackhole bh) throws SQLeichtException {
    try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT name FROM bench")) {
      while (SQLiteNative.step(stmt.stmt()) == SQLiteResultCode.ROW.code()) {
        MemorySegment seg = SQLiteNative.columnTextSegment(stmt.stmt(), 0);
        bh.consume(seg);
      }
    }
  }

  /** Baseline: read blob as byte[] (copies into Java heap). */
  @Benchmark
  public void blobCopy(Blackhole bh) throws SQLeichtException {
    try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT data FROM bench")) {
      while (SQLiteNative.step(stmt.stmt()) == SQLiteResultCode.ROW.code()) {
        byte[] b = SQLiteNative.columnBlob(stmt.stmt(), 0);
        bh.consume(b);
      }
    }
  }

  /** Zero-copy: read blob as MemorySegment (pointer into SQLite buffer). */
  @Benchmark
  public void blobZeroCopy(Blackhole bh) throws SQLeichtException {
    try (var stmt = SQLiteStatementHandle.prepare(conn, "SELECT data FROM bench")) {
      while (SQLiteNative.step(stmt.stmt()) == SQLiteResultCode.ROW.code()) {
        MemorySegment seg = SQLiteNative.columnBlobSegment(stmt.stmt(), 0);
        bh.consume(seg);
      }
    }
  }

  private static String generateText(String size) {
    return switch (size) {
      case "short" -> "hello world"; // 11 bytes
      case "medium" -> "x".repeat(256); // 256 bytes
      case "long" -> "x".repeat(4096); // 4 KB
      default -> throw new IllegalArgumentException(size);
    };
  }
}
