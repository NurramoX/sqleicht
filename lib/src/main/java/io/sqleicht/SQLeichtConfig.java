package io.sqleicht;

public final class SQLeichtConfig {
  private int threadCount = 2;
  private long maxLifetimeMs = 30 * 60 * 1000L;
  private int busyTimeoutMs = 5000;
  private String journalMode = "WAL";
  private String connectionInitSql;
  private long connectionTimeoutMs = 30_000L;
  private volatile boolean sealed;

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

  public SQLeichtConfig connectionInitSql(String connectionInitSql) {
    checkNotSealed();
    this.connectionInitSql = connectionInitSql;
    return this;
  }

  public SQLeichtConfig connectionTimeoutMs(long connectionTimeoutMs) {
    checkNotSealed();
    this.connectionTimeoutMs = connectionTimeoutMs;
    return this;
  }

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

  public String connectionInitSql() {
    return connectionInitSql;
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
    if (connectionTimeoutMs < 250) {
      throw new IllegalArgumentException(
          "connectionTimeoutMs must be >= 250, was " + connectionTimeoutMs);
    }
  }

  private void checkNotSealed() {
    if (sealed) {
      throw new IllegalStateException("Configuration is sealed — pool already started");
    }
  }
}
