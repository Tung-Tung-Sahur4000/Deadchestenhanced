package me.crylonz.deadchest;

import me.crylonz.deadchest.db.ChestDataRepository;
import me.crylonz.deadchest.db.SQLExecutor;
import me.crylonz.deadchest.db.SQLite;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Boots an isolated SQLite storage for the tests that go through the real
 * persistence layer.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    /**
     * Creates an empty database for the given plugin and installs it on
     * {@link DeadChestLoader}.
     *
     * @param plugin plugin owning the data folder
     */
    public static void start(Plugin plugin) {
        try {
            DeadChestLoader.sqlExecutor = new SQLExecutor();
            DeadChestLoader.db = new SQLite(plugin);

            Path dataFolder = plugin.getDataFolder().toPath();
            Files.createDirectories(dataFolder);
            Files.deleteIfExists(dataFolder.resolve("data.db"));

            DeadChestLoader.db.init();
            ChestDataRepository.initTable(() -> {
            });
            await();
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the test database", e);
        }
    }

    /**
     * Closes the database and resets the executor for the next test.
     */
    public static void stop() {
        if (DeadChestLoader.sqlExecutor != null) {
            DeadChestLoader.sqlExecutor.shutdown();
            DeadChestLoader.sqlExecutor = new SQLExecutor();
        }
        if (DeadChestLoader.db != null) {
            DeadChestLoader.db.close();
        }
    }

    /**
     * Waits until every queued database task has been executed.
     */
    public static void await() {
        CountDownLatch latch = new CountDownLatch(1);
        DeadChestLoader.sqlExecutor.runAsync(latch::countDown);
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test database");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
