package io.sqleicht.core;

import io.sqleicht.SQLeichtConfig;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class ConnectionExecutor implements AutoCloseable {
  private static final int RUNNING = 0;
  private static final int SHUTDOWN = 1;

  private final SQLeichtConfig config;
  private final ConnectionSlot[] slots;
  private final Semaphore semaphore;
  private final ThreadLocal<ConnectionSlot> heldSlot = new ThreadLocal<>();
  private final Thread housekeepingThread;
  private volatile int poolState = RUNNING;

  public ConnectionExecutor(String path, SQLeichtConfig config) {
    this.config = config;
    int n = config.threadCount();
    this.slots = new ConnectionSlot[n];
    this.semaphore = new Semaphore(n, true);

    // :memory: creates separate databases per connection — use shared cache URI instead
    int openFlags = SQLiteOpenFlag.DEFAULT;
    String effectivePath = path;
    if (":memory:".equals(path) && n > 1) {
      effectivePath = "file::memory:?cache=shared";
      openFlags =
          (openFlags & ~SQLiteOpenFlag.PRIVATECACHE & ~SQLiteOpenFlag.NOMUTEX)
              | SQLiteOpenFlag.URI
              | SQLiteOpenFlag.SHAREDCACHE;
    }

    for (int i = 0; i < n; i++) {
      slots[i] = new ConnectionSlot(effectivePath, openFlags, config);
    }

    // Open connections sequentially to avoid PRAGMA journal_mode write-lock contention
    for (int i = 0; i < n; i++) {
      try {
        slots[i].openConnection();
      } catch (SQLeichtException e) {
        for (int j = 0; j < i; j++) {
          slots[j].closeConnection();
        }
        throw new RuntimeException("Failed to open connection pool: " + e.getMessage(), e);
      }
    }

    housekeepingThread =
        Thread.ofVirtual().name("sqleicht-housekeeping").start(this::housekeepingLoop);
  }

  public <T> T submit(TaskFunction<T> task) throws SQLeichtException {
    if (poolState != RUNNING) {
      throw new IllegalStateException("Pool is shut down");
    }

    // Reentrant: already holding a slot on this thread
    ConnectionSlot held = heldSlot.get();
    if (held != null) {
      SQLeichtConnection conn = new SQLeichtConnection(held.connection());
      return task.apply(conn);
    }

    boolean acquired;
    try {
      acquired = semaphore.tryAcquire(config.connectionTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLeichtException(0, 0, "Interrupted while waiting for connection slot");
    }

    if (!acquired) {
      throw new SQLeichtException(
          0,
          0,
          "Connection timeout — no slot available within " + config.connectionTimeoutMs() + "ms");
    }

    try {
      ConnectionSlot slot = acquireSlot();
      try {
        // Handle evict (max-lifetime rotation) or reopen (after idle close)
        if (slot.evict) {
          try {
            slot.rotate();
          } catch (SQLeichtException e) {
            // rotation failed — continue with existing connection
          }
        } else if (!slot.isConnectionOpen()) {
          try {
            slot.openConnection();
          } catch (SQLeichtException e) {
            // reopen failed — task will fail with a clear error
          }
        }

        heldSlot.set(slot);
        try {
          SQLeichtConnection conn = new SQLeichtConnection(slot.connection());
          return task.apply(conn);
        } finally {
          slot.lastAccessed = System.nanoTime();
          heldSlot.remove();
        }
      } finally {
        slot.lock().unlock();
      }
    } finally {
      semaphore.release();
    }
  }

  public int activeCount() {
    int count = 0;
    for (ConnectionSlot slot : slots) {
      if (slot.lock().isLocked()) count++;
    }
    return count;
  }

  public int idleCount() {
    int count = 0;
    for (ConnectionSlot slot : slots) {
      if (!slot.lock().isLocked() && slot.isConnectionOpen()) count++;
    }
    return count;
  }

  public int pendingCount() {
    return semaphore.getQueueLength();
  }

  public int threadCount() {
    return config.threadCount();
  }

  @Override
  public void close() {
    poolState = SHUTDOWN;

    // Drain semaphore so no new acquires succeed
    semaphore.drainPermits();

    if (housekeepingThread != null) {
      housekeepingThread.interrupt();
    }

    // Wait for in-flight tasks then close connections (shared arena — any thread can close)
    for (ConnectionSlot slot : slots) {
      try {
        if (slot.lock().tryLock(5, TimeUnit.SECONDS)) {
          try {
            slot.closeConnection();
          } finally {
            slot.lock().unlock();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Spins to find an unlocked slot. The semaphore guarantees at most N concurrent callers for N
   * slots, so at least one slot will become available.
   */
  private ConnectionSlot acquireSlot() {
    while (true) {
      for (ConnectionSlot slot : slots) {
        if (slot.lock().tryLock()) {
          return slot;
        }
      }
      Thread.yield();
    }
  }

  private void housekeepingLoop() {
    long maxLifetimeNanos = config.maxLifetimeMs() * 1_000_000L;
    long idleTimeoutNanos = config.idleTimeoutMs() * 1_000_000L;
    while (poolState == RUNNING) {
      try {
        TimeUnit.MILLISECONDS.sleep(config.housekeepingIntervalMs());
      } catch (InterruptedException e) {
        break;
      }

      long now = System.nanoTime();
      for (ConnectionSlot slot : slots) {
        if (!slot.lock().tryLock()) continue; // skip busy slots
        try {
          // Max lifetime eviction with random variance to prevent thundering herd
          long variance = (long) (Math.random() * ((double) maxLifetimeNanos / 4));
          long effectiveLifetime = maxLifetimeNanos - variance;
          if (slot.isConnectionOpen() && now - slot.createdAt > effectiveLifetime) {
            slot.evict = true;
            continue;
          }

          // Idle timeout: close directly (shared arena allows cross-thread close)
          if (idleTimeoutNanos > 0
              && slot.isConnectionOpen()
              && (now - slot.lastAccessed) > idleTimeoutNanos) {
            slot.closeConnection();
          }
        } finally {
          slot.lock().unlock();
        }
      }
    }
  }
}
