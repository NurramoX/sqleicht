package io.sqleicht.core;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.sqleicht.SQLeichtConfig;
import io.sqleicht.TaskFunction;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class ConnectionExecutor implements AutoCloseable {
  private static final int RUNNING = 0;
  private static final int SHUTDOWN = 1;

  // SQLite's built-in busy handler delay schedule (nanoseconds)
  private static final long[] BUSY_DELAYS_NS = {
    1_000_000, 2_000_000, 5_000_000, 10_000_000, 15_000_000,
    20_000_000, 25_000_000, 25_000_000, 25_000_000, 50_000_000,
    50_000_000, 100_000_000
  };

  static final MemorySegment BUSY_CALLBACK;

  static {
    try {
      var mh =
          MethodHandles.lookup()
              .findStatic(
                  ConnectionExecutor.class,
                  "busyCallback",
                  MethodType.methodType(int.class, MemorySegment.class, int.class));
      BUSY_CALLBACK =
          Linker.nativeLinker()
              .upcallStub(mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), Arena.global());
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final ScopedValue<ConnectionSlot> HELD_SLOT = ScopedValue.newInstance();
  private static final ScopedValue<Long> DEADLINE = ScopedValue.newInstance();

  private final SQLeichtConfig config;
  private final ConnectionSlot[] slots;
  private final Semaphore semaphore;
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
    if (HELD_SLOT.isBound()) {
      SQLeichtConnection conn = new SQLeichtConnection(HELD_SLOT.get().connection());
      return task.apply(conn);
    }

    long deadlineNanos = System.nanoTime() + config.connectionTimeoutMs() * 1_000_000L;

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
      ConnectionSlot slot = acquireSlot(deadlineNanos);
      try {
        // Handle evict (max-lifetime rotation) or reopen (after idle close)
        if (slot.evict) {
          slot.rotate();
        } else if (!slot.isConnectionOpen()) {
          slot.openConnection();
        }

        try {
          SQLeichtConnection conn = new SQLeichtConnection(slot.connection());
          try {
            return ScopedValue.where(HELD_SLOT, slot)
                .where(DEADLINE, deadlineNanos)
                .call(() -> task.apply(conn));
          } catch (SQLeichtException | RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        } finally {
          slot.lastAccessed = System.nanoTime();
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

    // Close all connections concurrently (shared arena allows cross-thread close)
    try (var scope = StructuredTaskScope.open()) {
      for (ConnectionSlot slot : slots) {
        scope.fork(
            () -> {
              closeSlot(slot);
              return null;
            });
      }
      scope.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void closeSlot(ConnectionSlot slot) {
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

  /**
   * Upcall from SQLite's busy handler. Runs on the same thread as the caller, so ScopedValues are
   * still bound. pArg layout: [0] busyTimeoutNanos (long), [8] sequenceStartNanos (long, mutable).
   */
  @SuppressWarnings("unused") // called via MethodHandle upcall
  static int busyCallback(MemorySegment pArg, int count) {
    long now = System.nanoTime();
    pArg = pArg.reinterpret(16);

    // Check operation deadline (propagated from connectionTimeoutMs)
    if (DEADLINE.isBound() && now >= DEADLINE.get()) return 0;

    // Check per-connection busy timeout cap
    long busyTimeoutNanos = pArg.get(JAVA_LONG, 0);
    if (busyTimeoutNanos > 0) {
      if (count == 0) pArg.set(JAVA_LONG, 8, now);
      if (now - pArg.get(JAVA_LONG, 8) >= busyTimeoutNanos) return 0;
    } else if (!DEADLINE.isBound()) {
      return 0; // no busy timeout and no operation deadline — don't retry
    }

    // Backoff sleep matching SQLite's built-in pattern
    int idx = Math.min(count, BUSY_DELAYS_NS.length - 1);
    LockSupport.parkNanos(BUSY_DELAYS_NS[idx]);
    return 1;
  }

  /**
   * Spin-then-park to find an unlocked slot. The semaphore guarantees at most N concurrent callers
   * for N slots, so at least one slot will become available. We spin briefly (cheap under low
   * contention), then park to avoid burning CPU under sustained contention. Respects the overall
   * connection deadline to avoid exceeding the user's configured timeout.
   */
  private ConnectionSlot acquireSlot(long deadlineNanos) throws SQLeichtException {
    int spins = 0;
    while (true) {
      for (ConnectionSlot slot : slots) {
        if (slot.lock().tryLock()) {
          return slot;
        }
      }
      if (System.nanoTime() >= deadlineNanos) {
        throw new SQLeichtException(
            0, 0, "Connection timeout — deadline exceeded during slot acquisition");
      }
      if (spins < 8) {
        Thread.onSpinWait();
        spins++;
      } else {
        LockSupport.parkNanos(1_000); // 1µs — yield the thread instead of burning CPU
      }
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
          long variance = ThreadLocalRandom.current().nextLong(maxLifetimeNanos / 4);
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
