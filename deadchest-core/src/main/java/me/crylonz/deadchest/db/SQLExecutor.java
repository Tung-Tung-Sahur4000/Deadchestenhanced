package me.crylonz.deadchest.db;

import me.crylonz.deadchest.DeadChestLoader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Single threaded database executor.
 * <p>
 * Every statement runs on this one thread, which keeps the shared SQLite
 * connection free of cross thread transaction interleaving and makes
 * {@link #runBlocking(Runnable, long)} a safe way to obtain a durable write from
 * the server thread.
 */
public class SQLExecutor {

    private static final ThreadLocal<Boolean> ON_DB_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ExecutorService pool;

    public SQLExecutor() {
        this.pool = Executors.newSingleThreadExecutor();
    }

    /**
     * Queues a task. Failures are logged instead of being swallowed by the
     * discarded {@link Future}, otherwise a chest that fails to be written or
     * deleted disappears silently and comes back on the next restart.
     *
     * @param task task to run on the database thread
     */
    public void runAsync(Runnable task) {
        pool.submit(() -> execute(task, true));
    }

    /**
     * Runs a task on the database thread and waits for its completion.
     * <p>
     * Used for the writes that must have reached the disk before the server goes
     * on, typically the deadchest insert performed while the player inventory is
     * about to be cleared.
     *
     * @param task      task to run
     * @param timeoutMs maximum wait in milliseconds
     * @return {@code true} when the task completed successfully in time
     */
    public boolean runBlocking(Runnable task, long timeoutMs) {
        if (ON_DB_THREAD.get()) {
            // Re-entrant call from a database task: running inline is the only
            // option, waiting on ourselves would deadlock the single thread.
            return execute(task, true);
        }

        try {
            Future<?> future = pool.submit(() -> execute(task, false));
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            DeadChestLoader.log.severe("[DeadChest] Database write timed out after " + timeoutMs + "ms");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            DeadChestLoader.log.log(Level.SEVERE, "[DeadChest] Database write failed", e);
            return false;
        }
    }

    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @param task           task to execute
     * @param swallowFailure {@code true} to log and absorb the failure,
     *                       {@code false} to propagate it to the caller
     * @return {@code true} when the task ran without error
     */
    private boolean execute(Runnable task, boolean swallowFailure) {
        ON_DB_THREAD.set(Boolean.TRUE);
        try {
            task.run();
            return true;
        } catch (RuntimeException | Error t) {
            if (!swallowFailure) {
                throw t;
            }
            DeadChestLoader.log.log(Level.SEVERE, "[DeadChest] Database task failed", t);
            return false;
        } finally {
            ON_DB_THREAD.set(Boolean.FALSE);
        }
    }
}
