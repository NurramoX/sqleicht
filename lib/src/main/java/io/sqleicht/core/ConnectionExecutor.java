package io.sqleicht.core;

import io.sqleicht.SQLeichtConfig;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ConnectionExecutor implements AutoCloseable {
  private static final int RUNNING = 0;
  private static final int SHUTDOWN = 1;

  private final SQLeichtConfig config;
  private final String path;
  private final ConnectionSlot[] slots;
  private final Thread[] threads;
  private final LinkedBlockingQueue<Runnable>[] queues;
  private final Semaphore semaphore;
  private final AtomicInteger nextThread = new AtomicInteger();
  private final Thread housekeepingThread;
  private volatile int poolState = RUNNING;

  @SuppressWarnings("unchecked")
  public ConnectionExecutor(String path, SQLeichtConfig config) {
    this.config = config;
    int n = config.threadCount();
    this.slots = new ConnectionSlot[n];
    this.threads = new Thread[n];
    this.queues = new LinkedBlockingQueue[n];
    this.semaphore = new Semaphore(n, true);

    // :memory: creates separate databases per connection — use shared cache URI instead
    // Drop NOMUTEX with shared cache — SQLite needs internal locking for shared B-tree structures
    int openFlags = SQLiteOpenFlag.DEFAULT;
    String effectivePath = path;
    if (":memory:".equals(path) && n > 1) {
      effectivePath = "file::memory:?cache=shared";
      openFlags =
          (openFlags & ~SQLiteOpenFlag.PRIVATECACHE & ~SQLiteOpenFlag.NOMUTEX)
              | SQLiteOpenFlag.URI
              | SQLiteOpenFlag.SHAREDCACHE;
    }
    this.path = effectivePath;

    for (int i = 0; i < n; i++) {
      queues[i] = new LinkedBlockingQueue<>();
      slots[i] =
          new ConnectionSlot(
              effectivePath,
              openFlags,
              config.busyTimeoutMs(),
              config.journalMode(),
              config.connectionInitSql());
    }

    // Open connections one at a time to avoid PRAGMA journal_mode write-lock contention
    ThreadFactory factory = threadFactory();
    AtomicReference<SQLeichtException> startupError = new AtomicReference<>();

    for (int i = 0; i < n; i++) {
      CountDownLatch ready = new CountDownLatch(1);
      final int index = i;
      threads[i] = factory.newThread(() -> workerLoop(index, ready, startupError));
      threads[i].start();

      try {
        ready.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        close();
        throw new RuntimeException("Interrupted while starting connection pool", e);
      }

      SQLeichtException err = startupError.get();
      if (err != null) {
        close();
        throw new RuntimeException("Failed to open connection pool: " + err.getMessage(), err);
      }
    }

    housekeepingThread =
        Thread.ofVirtual().name("sqleicht-housekeeping").start(this::housekeepingLoop);
  }

  public <T> T submit(TaskFunction<T> task) throws SQLeichtException {
    if (poolState != RUNNING) {
      throw new IllegalStateException("Pool is shut down");
    }

    // Re-entrant call: already on a worker thread — execute inline to avoid deadlock
    int inlineIndex = workerIndex();
    if (inlineIndex >= 0) {
      ConnectionSlot slot = slots[inlineIndex];
      SQLeichtConnection conn = new SQLeichtConnection(slot.connection());
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
      int index = Math.floorMod(nextThread.getAndIncrement(), config.threadCount());
      FutureTask<T> future = new FutureTask<>(toCallable(task, index));
      queues[index].add(future);

      try {
        return future.get();
      } catch (java.util.concurrent.ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SQLeichtException se) {
          throw se;
        }
        if (cause instanceof RuntimeException re) {
          throw re;
        }
        if (cause instanceof Error err) {
          throw err;
        }
        throw new SQLeichtException(0, 0, "Task failed: " + cause.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SQLeichtException(0, 0, "Interrupted while waiting for task result");
      }
    } finally {
      semaphore.release();
    }
  }

  /** Returns the worker index if the current thread is a worker thread, or -1 otherwise. */
  private int workerIndex() {
    Thread current = Thread.currentThread();
    for (int i = 0; i < threads.length; i++) {
      if (threads[i] == current) return i;
    }
    return -1;
  }

  public int activeCount() {
    int count = 0;
    for (ConnectionSlot slot : slots) {
      if (slot.getState() == ConnectionSlot.IN_USE) count++;
    }
    return count;
  }

  public int idleCount() {
    int count = 0;
    for (ConnectionSlot slot : slots) {
      if (slot.getState() == ConnectionSlot.NOT_IN_USE) count++;
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

    // Enqueue poison pills to wake worker threads
    for (int i = 0; i < threads.length; i++) {
      if (queues[i] != null) {
        queues[i].add(() -> {});
      }
    }

    for (Thread t : threads) {
      if (t != null) {
        try {
          t.join(5000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    // Connections are closed by their owning worker threads (confined arena constraint)
  }

  private void workerLoop(
      int index, CountDownLatch ready, AtomicReference<SQLeichtException> startupError) {
    ConnectionSlot slot = slots[index];
    try {
      slot.openConnection();
    } catch (SQLeichtException e) {
      startupError.compareAndSet(null, e);
      ready.countDown();
      return;
    }
    ready.countDown();

    while (poolState == RUNNING) {
      try {
        Runnable task = queues[index].poll(1, TimeUnit.SECONDS);
        if (task == null) continue;

        // Check eviction between tasks
        if (slot.evict) {
          try {
            slot.rotate();
          } catch (SQLeichtException e) {
            // rotation failed — continue with existing connection
          }
        }

        slot.casState(ConnectionSlot.NOT_IN_USE, ConnectionSlot.IN_USE);
        try {
          task.run();
        } finally {
          slot.lastAccessed = System.nanoTime();
          slot.casState(ConnectionSlot.IN_USE, ConnectionSlot.NOT_IN_USE);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    // Drain remaining tasks
    Runnable remaining;
    while ((remaining = queues[index].poll()) != null) {
      slot.casState(ConnectionSlot.NOT_IN_USE, ConnectionSlot.IN_USE);
      try {
        remaining.run();
      } finally {
        slot.lastAccessed = System.nanoTime();
        slot.casState(ConnectionSlot.IN_USE, ConnectionSlot.NOT_IN_USE);
      }
    }

    // Close connection on the owning thread (confined arena constraint)
    slot.closeConnection();
  }

  private void housekeepingLoop() {
    long maxLifetimeNanos = config.maxLifetimeMs() * 1_000_000L;
    while (poolState == RUNNING) {
      try {
        Thread.sleep(30_000);
      } catch (InterruptedException e) {
        break;
      }

      long now = System.nanoTime();
      for (ConnectionSlot slot : slots) {
        // Add random variance: actual lifetime = maxLifetime - random(0, maxLifetime/4)
        long variance = (long) (Math.random() * (maxLifetimeNanos / 4));
        long effectiveLifetime = maxLifetimeNanos - variance;
        if (now - slot.createdAt > effectiveLifetime) {
          slot.evict = true;
        }
      }
    }
  }

  private <T> Callable<T> toCallable(TaskFunction<T> task, int slotIndex) {
    return () -> {
      ConnectionSlot slot = slots[slotIndex];
      SQLeichtConnection conn = new SQLeichtConnection(slot.connection());
      return task.apply(conn);
    };
  }

  private ThreadFactory threadFactory() {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, "sqleicht-ffi-" + counter.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
  }
}
