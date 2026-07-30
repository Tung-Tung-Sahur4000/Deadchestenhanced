package me.crylonz.deadchest.db;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class SQLite {
    private final Plugin plugin;
    private Connection conn;

    public SQLite(Plugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void init() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            Path dbPath = plugin.getDataFolder().toPath().resolve("data.db");
            String url = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");

            conn = DriverManager.getConnection(url);

            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                // FULL, not NORMAL: with WAL + NORMAL a committed transaction is
                // only in the OS page cache, so a host crash or a power loss can
                // roll back deadchest writes while the world data survives, which
                // duplicates or destroys items. Deadchest writes are rare and off
                // the server thread, the extra fsync is not worth the risk.
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA foreign_keys=ON");
            }

        } catch (SQLException e) {
            throw new RuntimeException("SQLite init failed", e);
        }
    }

    public synchronized Connection connection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            init();
        }
        return conn;
    }

    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
        }
    }
}

