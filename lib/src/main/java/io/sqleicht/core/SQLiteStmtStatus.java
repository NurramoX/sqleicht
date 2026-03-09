package io.sqleicht.core;

public final class SQLiteStmtStatus {
  public static final int FULLSCAN_STEP = 1;
  public static final int SORT = 2;
  public static final int AUTOINDEX = 3;
  public static final int VM_STEP = 4;
  public static final int REPREPARE = 5;
  public static final int RUN = 6;
  public static final int FILTER_MISS = 7;
  public static final int FILTER_HIT = 8;
  public static final int MEMUSED = 99;

  private SQLiteStmtStatus() {}
}
