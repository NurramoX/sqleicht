package io.sqleicht.ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.sqleicht.core.SQLeichtException;
import io.sqleicht.core.SQLiteResultCode;
import io.sqleicht.util.Utf8;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

public final class SQLiteNative {

  private static final MemorySegment SQLITE_STATIC = MemorySegment.NULL;

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIB = SQLiteLibrary.instance();

  // --- Connection management ---

  private static final MethodHandle OPEN_V2 =
      downcall(
          "sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

  private static final MethodHandle CLOSE_V2 =
      downcall("sqlite3_close_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  // --- Error reporting ---

  private static final MethodHandle ERRMSG =
      downcall("sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS));

  private static final MethodHandle ERRCODE =
      downcall("sqlite3_errcode", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  private static final MethodHandle EXTENDED_ERRCODE =
      downcall("sqlite3_extended_errcode", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  // --- Statement lifecycle ---

  static final int SQLITE_PREPARE_PERSISTENT = 0x01;

  private static final MethodHandle PREPARE_V3 =
      downcall(
          "sqlite3_prepare_v3",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

  private static final MethodHandle STEP =
      downcall("sqlite3_step", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  private static final MethodHandle RESET =
      downcall("sqlite3_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  private static final MethodHandle FINALIZE =
      downcall("sqlite3_finalize", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  private static final MethodHandle CLEAR_BINDINGS =
      downcall("sqlite3_clear_bindings", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  // --- Binding parameters ---

  private static final MethodHandle BIND_INT =
      downcall("sqlite3_bind_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

  private static final MethodHandle BIND_INT64 =
      downcall("sqlite3_bind_int64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));

  private static final MethodHandle BIND_DOUBLE =
      downcall(
          "sqlite3_bind_double", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE));

  private static final MethodHandle BIND_TEXT =
      downcall(
          "sqlite3_bind_text",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

  private static final MethodHandle BIND_BLOB =
      downcall(
          "sqlite3_bind_blob",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

  private static final MethodHandle BIND_NULL =
      downcall("sqlite3_bind_null", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  private static final MethodHandle BIND_PARAMETER_COUNT =
      downcall("sqlite3_bind_parameter_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  // --- Reading columns ---

  private static final MethodHandle COLUMN_INT =
      downcall("sqlite3_column_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  private static final MethodHandle COLUMN_INT64 =
      downcall("sqlite3_column_int64", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));

  private static final MethodHandle COLUMN_DOUBLE =
      downcall("sqlite3_column_double", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT));

  private static final MethodHandle COLUMN_TEXT =
      downcall("sqlite3_column_text", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  private static final MethodHandle COLUMN_BLOB =
      downcall("sqlite3_column_blob", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  private static final MethodHandle COLUMN_BYTES =
      downcall("sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  private static final MethodHandle COLUMN_TYPE =
      downcall("sqlite3_column_type", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  private static final MethodHandle COLUMN_COUNT =
      downcall("sqlite3_column_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  private static final MethodHandle COLUMN_NAME =
      downcall("sqlite3_column_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  // TODO: add sqlite3_column_origin_name and sqlite3_table_column_metadata for richer
  //  column metadata without JDBC-style secondary queries (cheap pointer reads)

  // --- Miscellaneous ---

  private static final MethodHandle BUSY_TIMEOUT =
      downcall("sqlite3_busy_timeout", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  private static final MethodHandle CHANGES64 =
      downcall("sqlite3_changes64", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

  private static final MethodHandle LAST_INSERT_ROWID =
      downcall("sqlite3_last_insert_rowid", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

  private static final MethodHandle EXEC =
      downcall(
          "sqlite3_exec",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  private static final MethodHandle STMT_STATUS =
      downcall("sqlite3_stmt_status", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

  private static final MethodHandle LIBVERSION =
      downcall("sqlite3_libversion", FunctionDescriptor.of(ADDRESS));

  private SQLiteNative() {}

  // ===== Connection management =====

  public static MemorySegment open(Arena arena, String filename, int flags)
      throws SQLeichtException {
    MemorySegment filenameStr = Utf8.allocate(arena, filename);
    MemorySegment ppDb = arena.allocate(ADDRESS);
    int rc;
    try {
      rc = (int) OPEN_V2.invokeExact(filenameStr, ppDb, flags, MemorySegment.NULL);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    MemorySegment db = ppDb.get(ADDRESS, 0);
    if (rc != 0) {
      String msg = MemorySegment.NULL.equals(db) ? "failed to open database" : errmsg(db);
      if (!MemorySegment.NULL.equals(db)) {
        try {
          CLOSE_V2.invokeExact(db);
        } catch (Throwable ignored) {
        }
      }
      throw SQLeichtException.fromCode(rc, msg + " — path: " + filename);
    }
    return db;
  }

  public static void close(MemorySegment db) throws SQLeichtException {
    int rc;
    try {
      rc = (int) CLOSE_V2.invokeExact(db);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to close database");
    }
  }

  // ===== Error reporting =====

  public static String errmsg(MemorySegment db) {
    try {
      MemorySegment ptr = (MemorySegment) ERRMSG.invokeExact(db);
      return Utf8.read(ptr);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static int errcode(MemorySegment db) {
    try {
      return (int) ERRCODE.invokeExact(db);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static int extendedErrcode(MemorySegment db) {
    try {
      return (int) EXTENDED_ERRCODE.invokeExact(db);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  // ===== Statement lifecycle =====

  public static MemorySegment prepare(Arena arena, MemorySegment db, String sql)
      throws SQLeichtException {
    return prepare(arena, db, sql, SQLITE_PREPARE_PERSISTENT);
  }

  public static MemorySegment prepare(Arena arena, MemorySegment db, String sql, int prepFlags)
      throws SQLeichtException {
    MemorySegment sqlStr = Utf8.allocate(arena, sql);
    MemorySegment ppStmt = arena.allocate(ADDRESS);
    int rc;
    try {
      rc = (int) PREPARE_V3.invokeExact(db, sqlStr, -1, prepFlags, ppStmt, MemorySegment.NULL);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromConnection(db, rc);
    }
    return ppStmt.get(ADDRESS, 0);
  }

  public static int step(MemorySegment stmt) {
    try {
      return (int) STEP.invokeExact(stmt);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static void stepUpdate(MemorySegment db, MemorySegment stmt) throws SQLeichtException {
    int rc = step(stmt);
    if (rc != SQLiteResultCode.DONE.code()) {
      throw SQLeichtException.fromConnection(db, rc);
    }
  }

  public static void reset(MemorySegment stmt) throws SQLeichtException {
    int rc;
    try {
      rc = (int) RESET.invokeExact(stmt);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to reset statement");
    }
  }

  public static void finalizeStmt(MemorySegment stmt) {
    try {
      int ignored = (int) FINALIZE.invokeExact(stmt);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static void clearBindings(MemorySegment stmt) throws SQLeichtException {
    int rc;
    try {
      rc = (int) CLEAR_BINDINGS.invokeExact(stmt);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to clear bindings");
    }
  }

  // ===== Binding parameters (1-based index) =====

  public static void bindInt(MemorySegment stmt, int index, int value) throws SQLeichtException {
    int rc;
    try {
      rc = (int) BIND_INT.invokeExact(stmt, index, value);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to bind int at index " + index);
    }
  }

  public static void bindLong(MemorySegment stmt, int index, long value) throws SQLeichtException {
    int rc;
    try {
      rc = (int) BIND_INT64.invokeExact(stmt, index, value);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to bind long at index " + index);
    }
  }

  public static void bindDouble(MemorySegment stmt, int index, double value)
      throws SQLeichtException {
    int rc;
    try {
      rc = (int) BIND_DOUBLE.invokeExact(stmt, index, value);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to bind double at index " + index);
    }
  }

  /**
   * Binds a text value to a prepared statement parameter. The arena is used to allocate the UTF-8
   * encoded string. Callers should use a short-lived, task-scoped arena rather than the
   * connection's arena to avoid unbounded accumulation of bind allocations over the connection's
   * lifetime.
   */
  public static void bindText(Arena arena, MemorySegment stmt, int index, String value)
      throws SQLeichtException {
    MemorySegment str = Utf8.allocate(arena, value);
    int rc;
    try {
      rc = (int) BIND_TEXT.invokeExact(stmt, index, str, -1, SQLITE_STATIC);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to bind text at index " + index);
    }
  }

  /**
   * Binds a blob value to a prepared statement parameter. The arena is used to allocate the native
   * buffer. Callers should use a short-lived, task-scoped arena rather than the connection's arena
   * to avoid unbounded accumulation of bind allocations over the connection's lifetime.
   */
  public static void bindBlob(Arena arena, MemorySegment stmt, int index, byte[] value)
      throws SQLeichtException {
    MemorySegment blob = arena.allocate(JAVA_BYTE, value.length);
    blob.copyFrom(MemorySegment.ofArray(value));
    int rc;
    try {
      rc = (int) BIND_BLOB.invokeExact(stmt, index, blob, value.length, SQLITE_STATIC);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to bind blob at index " + index);
    }
  }

  public static void bindNull(MemorySegment stmt, int index) throws SQLeichtException {
    int rc;
    try {
      rc = (int) BIND_NULL.invokeExact(stmt, index);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromCode(rc, "failed to bind null at index " + index);
    }
  }

  public static int bindParameterCount(MemorySegment stmt) {
    try {
      return (int) BIND_PARAMETER_COUNT.invokeExact(stmt);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  // ===== Reading columns (0-based index) =====

  public static int columnInt(MemorySegment stmt, int col) {
    try {
      return (int) COLUMN_INT.invokeExact(stmt, col);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static long columnLong(MemorySegment stmt, int col) {
    try {
      return (long) COLUMN_INT64.invokeExact(stmt, col);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static double columnDouble(MemorySegment stmt, int col) {
    try {
      return (double) COLUMN_DOUBLE.invokeExact(stmt, col);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static String columnText(MemorySegment stmt, int col) {
    try {
      MemorySegment ptr = (MemorySegment) COLUMN_TEXT.invokeExact(stmt, col);
      return Utf8.read(ptr);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  /**
   * Returns a MemorySegment view into SQLite's internal buffer for the text value at the given
   * column. The segment is valid only until the next {@code step()}, {@code reset()}, or {@code
   * finalizeStmt()} call on this statement. Use this for zero-copy batch reading — copy the segment
   * into your own arena before advancing.
   *
   * <p>Note: calls both sqlite3_column_text and sqlite3_column_bytes (two FFM calls). SQLite
   * guarantees column_bytes is valid after column_text without re-computing the value.
   */
  public static MemorySegment columnTextSegment(MemorySegment stmt, int col) {
    try {
      MemorySegment ptr = (MemorySegment) COLUMN_TEXT.invokeExact(stmt, col);
      if (ptr.equals(MemorySegment.NULL)) {
        return MemorySegment.NULL;
      }
      int size = (int) COLUMN_BYTES.invokeExact(stmt, col);
      return ptr.reinterpret(size);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static byte[] columnBlob(MemorySegment stmt, int col) {
    try {
      MemorySegment ptr = (MemorySegment) COLUMN_BLOB.invokeExact(stmt, col);
      if (ptr.equals(MemorySegment.NULL)) {
        return null;
      }
      int size = (int) COLUMN_BYTES.invokeExact(stmt, col);
      return ptr.reinterpret(size).toArray(JAVA_BYTE);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  /**
   * Returns a MemorySegment view into SQLite's internal buffer for the blob value at the given
   * column. The segment is valid only until the next {@code step()}, {@code reset()}, or {@code
   * finalizeStmt()} call on this statement. Use this for zero-copy batch reading.
   *
   * <p>Note: calls both sqlite3_column_blob and sqlite3_column_bytes (two FFM calls). SQLite
   * guarantees column_bytes is valid after column_blob without re-computing the value.
   */
  public static MemorySegment columnBlobSegment(MemorySegment stmt, int col) {
    try {
      MemorySegment ptr = (MemorySegment) COLUMN_BLOB.invokeExact(stmt, col);
      if (ptr.equals(MemorySegment.NULL)) {
        return MemorySegment.NULL;
      }
      int size = (int) COLUMN_BYTES.invokeExact(stmt, col);
      return ptr.reinterpret(size);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static int columnBytes(MemorySegment stmt, int col) {
    try {
      return (int) COLUMN_BYTES.invokeExact(stmt, col);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static int columnType(MemorySegment stmt, int col) {
    try {
      return (int) COLUMN_TYPE.invokeExact(stmt, col);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static int columnCount(MemorySegment stmt) {
    try {
      return (int) COLUMN_COUNT.invokeExact(stmt);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static String columnName(MemorySegment stmt, int col) {
    try {
      MemorySegment ptr = (MemorySegment) COLUMN_NAME.invokeExact(stmt, col);
      return Utf8.read(ptr);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  // ===== Miscellaneous =====

  public static void busyTimeout(MemorySegment db, int ms) throws SQLeichtException {
    int rc;
    try {
      rc = (int) BUSY_TIMEOUT.invokeExact(db, ms);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromConnection(db, rc);
    }
  }

  public static long changes(MemorySegment db) {
    try {
      return (long) CHANGES64.invokeExact(db);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static long lastInsertRowid(MemorySegment db) {
    try {
      return (long) LAST_INSERT_ROWID.invokeExact(db);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static void exec(Arena arena, MemorySegment db, String sql) throws SQLeichtException {
    MemorySegment sqlStr = Utf8.allocate(arena, sql);
    int rc;
    try {
      rc =
          (int)
              EXEC.invokeExact(
                  db, sqlStr, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
    if (rc != 0) {
      throw SQLeichtException.fromConnection(db, rc);
    }
  }

  public static int stmtStatus(MemorySegment stmt, int op, boolean reset) {
    try {
      return (int) STMT_STATUS.invokeExact(stmt, op, reset ? 1 : 0);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  public static String libversion() {
    try {
      MemorySegment ptr = (MemorySegment) LIBVERSION.invokeExact();
      return Utf8.read(ptr);
    } catch (Throwable t) {
      throw new AssertionError("FFM call failed", t);
    }
  }

  // ===== Internal =====

  private static MethodHandle downcall(String name, FunctionDescriptor desc) {
    MemorySegment symbol =
        LIB.find(name)
            .orElseThrow(
                () ->
                    new UnsatisfiedLinkError(
                        "SQLite symbol not found: "
                            + name
                            + ". Is the sqlite3 library loaded correctly?"));
    return LINKER.downcallHandle(symbol, desc);
  }
}
