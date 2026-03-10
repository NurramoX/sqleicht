package io.sqleicht;

public final class SQLeichtConfig {
  private int threadCount = 2;
  private long maxLifetimeMs = 30 * 60 * 1000L;
  private int busyTimeoutMs = 5000;
  private String journalMode = "WAL";
  private String synchronous = "NORMAL";
  private int cacheSize = -20000;
  private boolean foreignKeys = true;
  private String autoVacuum = "NONE";
  private String tempStore = "DEFAULT";
  private long mmapSize = 2_147_483_648L;
  private int pageSize = 8192;
  private String connectionInitSql;
  private long idleTimeoutMs = 10 * 60 * 1000L;
  private int statementCacheSize = 64;
  private long connectionTimeoutMs = 30_000L;
  private long housekeepingIntervalMs = 30_000L;
  private volatile boolean sealed;

  // --- Pool settings ---

  public SQLeichtConfig threadCount(int threadCount) {
    checkNotSealed();
    this.threadCount = threadCount;
    return this;
  }

  public SQLeichtConfig maxLifetimeMs(long maxLifetimeMs) {
    checkNotSealed();
    this.maxLifetimeMs = maxLifetimeMs;
    return this;
  }

  public SQLeichtConfig idleTimeoutMs(long idleTimeoutMs) {
    checkNotSealed();
    this.idleTimeoutMs = idleTimeoutMs;
    return this;
  }

  public SQLeichtConfig connectionTimeoutMs(long connectionTimeoutMs) {
    checkNotSealed();
    this.connectionTimeoutMs = connectionTimeoutMs;
    return this;
  }

  public SQLeichtConfig housekeepingIntervalMs(long housekeepingIntervalMs) {
    checkNotSealed();
    this.housekeepingIntervalMs = housekeepingIntervalMs;
    return this;
  }

  public SQLeichtConfig statementCacheSize(int statementCacheSize) {
    checkNotSealed();
    this.statementCacheSize = statementCacheSize;
    return this;
  }

  // --- SQLite connection settings ---

  public SQLeichtConfig busyTimeoutMs(int busyTimeoutMs) {
    checkNotSealed();
    this.busyTimeoutMs = busyTimeoutMs;
    return this;
  }

  public SQLeichtConfig journalMode(String journalMode) {
    checkNotSealed();
    this.journalMode = journalMode;
    return this;
  }

  public SQLeichtConfig synchronous(String synchronous) {
    checkNotSealed();
    this.synchronous = synchronous;
    return this;
  }

  public SQLeichtConfig cacheSize(int cacheSize) {
    checkNotSealed();
    this.cacheSize = cacheSize;
    return this;
  }

  public SQLeichtConfig foreignKeys(boolean foreignKeys) {
    checkNotSealed();
    this.foreignKeys = foreignKeys;
    return this;
  }

  public SQLeichtConfig autoVacuum(String autoVacuum) {
    checkNotSealed();
    this.autoVacuum = autoVacuum;
    return this;
  }

  public SQLeichtConfig tempStore(String tempStore) {
    checkNotSealed();
    this.tempStore = tempStore;
    return this;
  }

  public SQLeichtConfig mmapSize(long mmapSize) {
    checkNotSealed();
    this.mmapSize = mmapSize;
    return this;
  }

  public SQLeichtConfig pageSize(int pageSize) {
    checkNotSealed();
    this.pageSize = pageSize;
    return this;
  }

  public SQLeichtConfig connectionInitSql(String connectionInitSql) {
    checkNotSealed();
    this.connectionInitSql = connectionInitSql;
    return this;
  }

  // --- Getters ---

  public int threadCount() {
    return threadCount;
  }

  public long maxLifetimeMs() {
    return maxLifetimeMs;
  }

  public int busyTimeoutMs() {
    return busyTimeoutMs;
  }

  public String journalMode() {
    return journalMode;
  }

  public String synchronous() {
    return synchronous;
  }

  public int cacheSize() {
    return cacheSize;
  }

  public boolean foreignKeys() {
    return foreignKeys;
  }

  public String autoVacuum() {
    return autoVacuum;
  }

  public String tempStore() {
    return tempStore;
  }

  public long mmapSize() {
    return mmapSize;
  }

  public int pageSize() {
    return pageSize;
  }

  public String connectionInitSql() {
    return connectionInitSql;
  }

  public long idleTimeoutMs() {
    return idleTimeoutMs;
  }

  public long housekeepingIntervalMs() {
    return housekeepingIntervalMs;
  }

  public int statementCacheSize() {
    return statementCacheSize;
  }

  public long connectionTimeoutMs() {
    return connectionTimeoutMs;
  }

  void seal() {
    sealed = true;
  }

  void validate() {
    if (threadCount < 1) {
      throw new IllegalArgumentException("threadCount must be >= 1, was " + threadCount);
    }
    if (maxLifetimeMs < 30_000) {
      throw new IllegalArgumentException("maxLifetimeMs must be >= 30000, was " + maxLifetimeMs);
    }
    if (idleTimeoutMs != 0 && idleTimeoutMs < 1_000) {
      throw new IllegalArgumentException(
          "idleTimeoutMs must be 0 (disabled) or >= 1000, was " + idleTimeoutMs);
    }
    if (idleTimeoutMs != 0 && idleTimeoutMs >= maxLifetimeMs) {
      throw new IllegalArgumentException(
          "idleTimeoutMs ("
              + idleTimeoutMs
              + ") must be less than maxLifetimeMs ("
              + maxLifetimeMs
              + "), or set to 0 to disable");
    }
    if (statementCacheSize < 0) {
      throw new IllegalArgumentException(
          "statementCacheSize must be >= 0, was " + statementCacheSize);
    }
    if (connectionTimeoutMs < 250) {
      throw new IllegalArgumentException(
          "connectionTimeoutMs must be >= 250, was " + connectionTimeoutMs);
    }
    if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0) {
      throw new IllegalArgumentException(
          "pageSize must be a power of 2 between 512 and 65536, was " + pageSize);
    }
    if (mmapSize < 0) {
      throw new IllegalArgumentException("mmapSize must be >= 0, was " + mmapSize);
    }
    validatePragmaValue("journalMode", journalMode);
    validatePragmaValue("synchronous", synchronous);
    validatePragmaValue("autoVacuum", autoVacuum);
    validatePragmaValue("tempStore", tempStore);
  }

  private static void validatePragmaValue(String name, String value) {
    if (value == null) return;
    if (!value.matches("[A-Za-z_]+")) {
      throw new IllegalArgumentException(
          name + " must be a simple alphanumeric PRAGMA value, was '" + value + "'");
    }
  }

  private void checkNotSealed() {
    if (sealed) {
      throw new IllegalStateException("Configuration is sealed — pool already started");
    }
  }
}
