package io.sqleicht.core;

public final class SQLiteOpenFlag {
  public static final int READONLY = 0x00000001;
  public static final int READWRITE = 0x00000002;
  public static final int CREATE = 0x00000004;
  public static final int URI = 0x00000040;
  public static final int MEMORY = 0x00000080;
  public static final int NOMUTEX = 0x00008000;
  public static final int FULLMUTEX = 0x00010000;
  public static final int SHAREDCACHE = 0x00020000;
  public static final int PRIVATECACHE = 0x00040000;

  public static final int DEFAULT = READWRITE | CREATE | NOMUTEX | PRIVATECACHE;

  private SQLiteOpenFlag() {}
}
