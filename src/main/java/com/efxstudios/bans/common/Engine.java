package com.efxstudios.bans.common;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A single ordered database worker. SQL and cache updates never run on the game thread, and the change
 * feed is polled so several servers sharing one MySQL database see each other's punishments.
 */
public final class Engine implements AutoCloseable {
    @FunctionalInterface
    public interface Operation<T> {
        T run(Store store) throws Exception;
    }

    private static final int QUEUE_LIMIT = 256;
    private static final int PAGE = 500;
    private static final long ERROR_LOG_INTERVAL_MILLIS = 30_000;

    public final ActiveCache cache = new ActiveCache();
    private final Store store;
    private final ScheduledThreadPoolExecutor worker;
    private final Semaphore capacity = new Semaphore(QUEUE_LIMIT);
    private final AtomicBoolean hintQueued = new AtomicBoolean();
    private final Consumer<List<Store.Change>> observer;
    private final Consumer<Throwable> errors;
    // Only touched by the worker thread (and the constructor before it starts).
    private long cursor;
    private long lastErrorLog;
    private volatile long healthyAt;

    public Engine(Store store, long pollMillis, Consumer<List<Store.Change>> observer, Consumer<Throwable> errors) throws SQLException {
        this.store = store;
        this.observer = observer;
        this.errors = errors;
        Store.Snapshot snapshot = store.snapshot();
        cache.replace(snapshot.active());
        cursor = snapshot.cursor();
        healthyAt = System.currentTimeMillis();
        worker = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "efxBans-database");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(this::poll, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    /** False once the change feed could not be read for maxAgeMillis: callers then fail closed. */
    public boolean healthy(long maxAgeMillis) {
        return System.currentTimeMillis() - healthyAt < maxAgeMillis;
    }

    /** Queues work on the database thread. Fails fast instead of growing without bound. */
    public <T> CompletableFuture<T> call(Operation<T> operation) {
        if (!capacity.tryAcquire()) return CompletableFuture.failedFuture(new RejectedExecutionException("Database work queue is full."));
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    T value = operation.run(store);
                    poll();
                    result.complete(value);
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                } finally {
                    capacity.release();
                }
            });
        } catch (RejectedExecutionException e) {
            capacity.release();
            result.completeExceptionally(e);
        }
        return result;
    }

    /** Requests an extra poll soon; repeated hints collapse into one. */
    public void hint() {
        if (!hintQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    poll();
                } finally {
                    hintQueued.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            hintQueued.set(false);
        }
    }

    private void poll() {
        try {
            List<Store.Change> changes = store.changes(cursor);
            if (!changes.isEmpty()) {
                cache.apply(changes);
                observer.accept(changes);
                cursor = changes.getLast().sequence();
            }
            healthyAt = System.currentTimeMillis();
            if (changes.size() == PAGE) hint();
        } catch (Throwable e) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLog > ERROR_LOG_INTERVAL_MILLIS) {
                lastErrorLog = now;
                errors.accept(e);
            }
        }
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
        store.close();
    }
}
