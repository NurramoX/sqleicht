package io.sqleicht.core;

public enum SQLiteResultCode {
  OK(0),
  ERROR(1),
  INTERNAL(2),
  PERM(3),
  ABORT(4),
  BUSY(5),
  LOCKED(6),
  NOMEM(7),
  READONLY(8),
  INTERRUPT(9),
  IOERR(10),
  CORRUPT(11),
  NOTFOUND(12),
  FULL(13),
  CANTOPEN(14),
  PROTOCOL(15),
  EMPTY(16),
  SCHEMA(17),
  TOOBIG(18),
  CONSTRAINT(19),
  MISMATCH(20),
  MISUSE(21),
  NOLFS(22),
  AUTH(23),
  FORMAT(24),
  RANGE(25),
  NOTADB(26),
  NOTICE(27),
  WARNING(28),
  ROW(100),
  DONE(101),

  // Extended — busy
  BUSY_RECOVERY(261),
  BUSY_SNAPSHOT(517),
  BUSY_TIMEOUT(773),

  // Extended — constraint
  CONSTRAINT_CHECK(275),
  CONSTRAINT_FOREIGNKEY(787),
  CONSTRAINT_NOTNULL(1299),
  CONSTRAINT_PRIMARYKEY(1555),
  CONSTRAINT_UNIQUE(2067),

  // Extended — ioerr
  IOERR_READ(266),
  IOERR_WRITE(778),
  IOERR_FSYNC(1034),

  // Extended — readonly
  READONLY_DBMOVED(1032),

  UNKNOWN(-1);

  private final int code;

  SQLiteResultCode(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  public boolean isError() {
    return this != OK && this != ROW && this != DONE;
  }

  public static SQLiteResultCode fromCode(int code) {
    for (SQLiteResultCode rc : values()) {
      if (rc.code == code) {
        return rc;
      }
    }
    return UNKNOWN;
  }
}
