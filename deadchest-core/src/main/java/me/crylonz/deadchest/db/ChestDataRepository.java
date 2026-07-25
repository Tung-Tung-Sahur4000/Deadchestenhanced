package me.crylonz.deadchest.db;

import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.integrity.ChestIntegrityState;
import me.crylonz.deadchest.utils.ItemBytes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.function.Consumer;

import static me.crylonz.deadchest.DeadChestLoader.db;
import static me.crylonz.deadchest.DeadChestLoader.sqlExecutor;

public class ChestDataRepository {

    /**
     * Maximum time the server thread waits for a write that must be on disk
     * before the game state moves on.
     */
    private static final long DURABLE_WRITE_TIMEOUT_MS = 3000L;

    private static final String INSERT_COLUMNS =
            "player_uuid, player_name, chest_world, chest_x, chest_y, chest_z, chest_yaw, chest_pitch, " +
                    "chest_date, is_infinity, is_removed_block, " +
                    "holo_world, holo_x, holo_y, holo_z, holo_yaw, holo_pitch, " +
                    "holographic_timer_id, holographic_status_id, holographic_owner_id, killer_uuid, world_name, xp_stored, inventory, " +
                    "death_id, integrity_state, integrity_seq, integrity_owner";

    private static final String INSERT_PLACEHOLDERS =
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";

    private static final String UPDATE_ASSIGNMENTS =
            "chest_date = ?, " +
                    "is_infinity = ?, " +
                    "is_removed_block = ?, " +
                    "holo_world = ?, " +
                    "holo_x = ?, " +
                    "holo_y = ?, " +
                    "holo_z = ?, " +
                    "holo_yaw = ?, " +
                    "holo_pitch = ?, " +
                    "holographic_timer_id = ?, " +
                    "holographic_status_id = ?, " +
                    "holographic_owner_id = ?, " +
                    "killer_uuid = ?, " +
                    "world_name = ?, " +
                    "xp_stored = ?, " +
                    "inventory = ?, " +
                    "death_id = ?, " +
                    "integrity_state = ?, " +
                    "integrity_seq = ?, " +
                    "integrity_owner = ?";

    /**
     * Identifies the row of one precise chest, used by deletions.
     * <p>
     * The death id is the real identity: several chests can share a block over
     * time, and a deletion keyed on the position alone used to remove whichever
     * row matched first, dropping a chest that was still in use and leaving the
     * looted one behind. The owner + position key is only kept for the rows
     * written before death ids existed.
     */
    private static final String IDENTITY_PREDICATE =
            "((death_id IS NOT NULL AND death_id = ?) " +
                    "OR (death_id IS NULL AND player_uuid = ? AND chest_world = ? AND chest_x = ? AND chest_y = ? AND chest_z = ?))";

    /**
     * Locates the row occupying a block for a player, used by the upsert paths:
     * one player can only have one chest per block, so this is what tells an
     * insert from an update.
     */
    private static final String SLOT_PREDICATE =
            "player_uuid = ? AND chest_world = ? AND chest_x = ? AND chest_y = ? AND chest_z = ?";

    public static void initTable(Runnable afterCreation) {
        sqlExecutor.runAsync(() -> {
            try (Statement st = db.connection().createStatement()) {
                st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS chest_data (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "player_uuid TEXT NOT NULL," +
                                "player_name TEXT NOT NULL," +
                                "chest_world TEXT NOT NULL," +
                                "chest_x INTEGER NOT NULL," +
                                "chest_y INTEGER NOT NULL," +
                                "chest_z INTEGER NOT NULL," +
                                "chest_yaw REAL NOT NULL," +
                                "chest_pitch REAL NOT NULL," +
                                "chest_date BIGINT NOT NULL," +
                                "is_infinity BOOLEAN NOT NULL," +
                                "is_removed_block BOOLEAN NOT NULL," +
                                "holo_world TEXT NOT NULL," +
                                "holo_x INTEGER NOT NULL," +
                                "holo_y INTEGER NOT NULL," +
                                "holo_z INTEGER NOT NULL," +
                                "holo_yaw REAL NOT NULL," +
                                "holo_pitch REAL NOT NULL," +
                                "holographic_timer_id TEXT NOT NULL," +
                                "holographic_status_id TEXT," +
                                "holographic_owner_id TEXT NOT NULL," +
                                "killer_uuid TEXT," +
                                "world_name TEXT NOT NULL," +
                                "xp_stored INTEGER NOT NULL," +
                                "inventory BLOB," +
                                "death_id TEXT," +
                                "integrity_state TEXT NOT NULL DEFAULT 'CONFIRMED'," +
                                "integrity_seq BIGINT NOT NULL DEFAULT 0," +
                                "integrity_owner TEXT" +
                                ")"
                );
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_player ON chest_data(player_uuid)");
                migrateSchema(st);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_location ON chest_data(chest_world, chest_x, chest_y, chest_z)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_death_id ON chest_data(death_id)");
                afterCreation.run();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create chest_data schema", e);
            }

        });
    }

    public static void saveAllAsync(Collection<ChestData> chests) {
        sqlExecutor.runAsync(() -> {
            ChestDataRepository.batchSave(chests);
        });
    }

    public static void saveAsync(@Nonnull final ChestData chest, @Nonnull final Consumer<Boolean> containsChestOnLoc) {
        sqlExecutor.runAsync(() -> {
            containsChestOnLoc.accept(ChestDataRepository.save(chest));
        });
    }

    public static void updateAsync(@Nonnull final ChestData chest, @Nonnull final Consumer<Boolean> updateInsert) {
        sqlExecutor.runAsync(() -> {
            updateInsert.accept(ChestDataRepository.update(chest));
        });
    }

    public static void removeAsync(@Nonnull final ChestData chest) {
        sqlExecutor.runAsync(() -> {
            ChestDataRepository.remove(chest);
        });
    }

    public static void removeBatchAsync(@Nonnull final Collection<ChestData> chest) {
        sqlExecutor.runAsync(() -> {
            ChestDataRepository.remove(chest);
        });
    }


    public static void clearAsync() {
        sqlExecutor.runAsync(ChestDataRepository::clear);
    }


    /**
     * Usage :
     * ChestDataRepository.findAllAsync(data -> {
     * player.sendMessage("Loaded " + data.size() + " chests!");
     * }, plugin);
     */
    public static void findAllAsync(Consumer<List<ChestData>> callback, Plugin plugin) {
        sqlExecutor.runAsync(() -> {
            List<ChestData> result = findAll();

            DeadChestLoader.getSchedulerAdapter().runGlobal(() -> callback.accept(result));
        });
    }


    public static void saveAll(Collection<ChestData> chests) {
        String sql = "INSERT INTO chest_data (" + INSERT_COLUMNS + ") VALUES (" + INSERT_PLACEHOLDERS + ")";

        try (Connection conn = db.connection();
             Statement clear = conn.createStatement();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // On efface tout avant
            clear.executeUpdate("DELETE FROM chest_data");

            // On batch tous les nouveaux coffres
            for (ChestData chest : chests) {
                bindInsert(ps, chest);
                ps.addBatch();
            }

            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void batchSave(Collection<ChestData> chests) {
        final String checkDuplicate = "SELECT 1 FROM chest_data WHERE " + SLOT_PREDICATE + " LIMIT 1";
        final String sqlInsert = "INSERT INTO chest_data (" + INSERT_COLUMNS + ") VALUES (" + INSERT_PLACEHOLDERS + ")";
        final String sqlUpdate = "UPDATE chest_data SET " + UPDATE_ASSIGNMENTS + " WHERE " + SLOT_PREDICATE;

        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement psCheck = conn.prepareStatement(checkDuplicate);
                 PreparedStatement psInsert = conn.prepareStatement(sqlInsert);
                 PreparedStatement psUpdate = conn.prepareStatement(sqlUpdate)) {
                boolean hasInserts = false;
                boolean hasUpdates = false;
                for (ChestData chest : chests) {
                    // Check if row exists
                    bindSlot(psCheck, 1, chest);

                    boolean exists;
                    try (ResultSet rs = psCheck.executeQuery()) {
                        exists = rs.next();
                    }
                    if (!exists) {
                        bindInsert(psInsert, chest);
                        psInsert.addBatch();
                        hasInserts = true;
                    } else {
                        int i = bindUpdate(psUpdate, chest);
                        bindSlot(psUpdate, i, chest);
                        psUpdate.addBatch();
                        hasUpdates = true;
                    }
                }
                if (hasInserts) {
                    psInsert.executeBatch();
                }
                if (hasUpdates) {
                    psUpdate.executeBatch();
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean update(@Nonnull final ChestData chest) {
        final String sqlUpdate = "UPDATE chest_data SET " + UPDATE_ASSIGNMENTS + " WHERE " + SLOT_PREDICATE;
        final String checkDublicate = "SELECT 1 FROM chest_data WHERE " + SLOT_PREDICATE + " LIMIT 1";

        try (Connection conn = db.connection();
             PreparedStatement psDublicate = conn.prepareStatement(checkDublicate);
             PreparedStatement ps = conn.prepareStatement(sqlUpdate)) {

            bindSlot(psDublicate, 1, chest);
            try (ResultSet rs = psDublicate.executeQuery()) {
                if (!rs.next()) {
                    save(chest);
                    return true;
                }
            }

            int i = bindUpdate(ps, chest);
            bindSlot(ps, i, chest);

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return false;
    }


    public static boolean save(@Nonnull final ChestData chest) {
        final String sql = "INSERT INTO chest_data (" + INSERT_COLUMNS + ") VALUES (" + INSERT_PLACEHOLDERS + ")";
        final String checkDublicate = "SELECT 1 FROM chest_data WHERE " + SLOT_PREDICATE + " LIMIT 1";

        try (Connection conn = db.connection();
             PreparedStatement psDublicate = conn.prepareStatement(checkDublicate);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindSlot(psDublicate, 1, chest);
            try (ResultSet rs = psDublicate.executeQuery()) {
                if (rs.next())
                    return true;
            }

            bindInsert(ps, chest);

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    public static void remove(@Nonnull final Collection<ChestData> chests) {
        String sql = "DELETE FROM chest_data WHERE " + IDENTITY_PREDICATE;

        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            boolean batchEmpty = true;
            for (ChestData chest : chests) {
                bindIdentity(ps, 1, chest);
                ps.addBatch();
                batchEmpty = false;
            }
            if (!batchEmpty)
                ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void remove(@Nonnull final ChestData chest) {
        String sql = "DELETE FROM chest_data WHERE " + IDENTITY_PREDICATE;

        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindIdentity(ps, 1, chest);

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void clear() {
        String sql = "DELETE FROM chest_data";

        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<ChestData> findAll() {
        List<ChestData> list = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT * FROM chest_data");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(deserializeChest(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    private static ChestData deserializeChest(ResultSet rs) throws SQLException {
        Location chestLoc = new Location(
                Bukkit.getWorld(rs.getString("chest_world")),
                rs.getInt("chest_x"),
                rs.getInt("chest_y"),
                rs.getInt("chest_z"),
                rs.getFloat("chest_yaw"),
                rs.getFloat("chest_pitch")
        );

        Location holoLoc = new Location(
                Bukkit.getWorld(rs.getString("holo_world")),
                rs.getInt("holo_x"),
                rs.getInt("holo_y"),
                rs.getInt("holo_z"),
                rs.getFloat("holo_yaw"),
                rs.getFloat("holo_pitch")
        );

        ChestData chestData = new ChestData(
                ItemBytes.fromBytesList(rs.getBytes("inventory")),
                chestLoc,
                rs.getString("player_name"),
                UUID.fromString(rs.getString("player_uuid")),
                new Date(rs.getLong("chest_date")),
                rs.getBoolean("is_infinity"),
                rs.getBoolean("is_removed_block"),
                holoLoc,
                UUID.fromString(rs.getString("holographic_timer_id")),
                rs.getString("holographic_status_id") == null ? null : UUID.fromString(rs.getString("holographic_status_id")),
                UUID.fromString(rs.getString("holographic_owner_id")),
                rs.getString("killer_uuid") == null ? null : UUID.fromString(rs.getString("killer_uuid")),
                rs.getString("world_name"),
                rs.getInt("xp_stored")
        );

        String storedDeathId = rs.getString("death_id");
        if (storedDeathId != null) {
            chestData.setDeathId(toUuid(storedDeathId));
        }
        chestData.setIntegrityState(ChestIntegrityState.fromStorage(rs.getString("integrity_state")));
        chestData.setIntegritySequence(rs.getLong("integrity_seq"));
        chestData.setIntegrityOwner(toUuid(rs.getString("integrity_owner")));
        return chestData;
    }

    // ---------------------------------------------------------------------
    // Durable writes
    //
    // These run on the database thread and are waited for, so the caller knows
    // the row reached the disk before the game state that depends on it changes
    // (clearing an inventory, handing items over to a player).
    // ---------------------------------------------------------------------

    /**
     * Inserts a chest and waits for the write to complete.
     *
     * @param chest chest to persist
     * @return {@code true} when the row is durably stored
     */
    public static boolean saveDurable(@Nonnull final ChestData chest) {
        final boolean[] alreadyPresent = new boolean[1];
        boolean completed = sqlExecutor.runBlocking(() -> alreadyPresent[0] = save(chest), DURABLE_WRITE_TIMEOUT_MS);
        return completed && !alreadyPresent[0];
    }

    /**
     * Deletes a chest and waits for the write to complete.
     *
     * @param chest chest to delete
     * @return {@code true} when the row is durably gone
     */
    public static boolean removeDurable(@Nonnull final ChestData chest) {
        return sqlExecutor.runBlocking(() -> remove(chest), DURABLE_WRITE_TIMEOUT_MS);
    }

    /**
     * Updates a chest and waits for the write to complete.
     *
     * @param chest chest to update
     * @return {@code true} when the new content is durably stored
     */
    public static boolean updateDurable(@Nonnull final ChestData chest) {
        return sqlExecutor.runBlocking(() -> update(chest), DURABLE_WRITE_TIMEOUT_MS);
    }

    /**
     * Persists only the integrity columns and waits for the write to complete.
     * Cheaper than {@link #update(ChestData)}: the inventory blob is untouched.
     *
     * @param chest chest whose integrity state changed
     * @return {@code true} when the new state is durably stored
     */
    public static boolean saveIntegrityDurable(@Nonnull final ChestData chest) {
        return sqlExecutor.runBlocking(() -> saveIntegrity(chest), DURABLE_WRITE_TIMEOUT_MS);
    }

    /**
     * Queues an integrity-only update.
     *
     * @param chest chest whose integrity state changed
     */
    public static void saveIntegrityAsync(@Nonnull final ChestData chest) {
        sqlExecutor.runAsync(() -> saveIntegrity(chest));
    }

    /**
     * Writes the integrity columns of an already stored chest.
     *
     * @param chest chest whose integrity state changed
     */
    public static void saveIntegrity(@Nonnull final ChestData chest) {
        final String sql = "UPDATE chest_data SET death_id = ?, integrity_state = ?, integrity_seq = ?, integrity_owner = ? " +
                "WHERE " + IDENTITY_PREDICATE;

        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = bindIntegrity(ps, 1, chest);
            bindIdentity(ps, i, chest);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------------
    // Statement binding
    // ---------------------------------------------------------------------

    private static void bindInsert(PreparedStatement ps, ChestData chest) throws SQLException {
        Location chestLoc = chest.getChestLocation();
        Location holoLoc = chest.getHolographicTimer();

        int i = 1;
        ps.setString(i++, chest.getPlayerStringUUID());
        ps.setString(i++, chest.getPlayerName());

        ps.setString(i++, chestLoc.getWorld().getName());
        ps.setInt(i++, chestLoc.getBlockX());
        ps.setInt(i++, chestLoc.getBlockY());
        ps.setInt(i++, chestLoc.getBlockZ());
        ps.setFloat(i++, chestLoc.getYaw());
        ps.setFloat(i++, chestLoc.getPitch());

        ps.setLong(i++, chest.getChestDate().getTime());
        ps.setBoolean(i++, chest.isInfinity());
        ps.setBoolean(i++, chest.isRemovedBlock());

        ps.setString(i++, holoLoc.getWorld().getName());
        ps.setInt(i++, holoLoc.getBlockX());
        ps.setInt(i++, holoLoc.getBlockY());
        ps.setInt(i++, holoLoc.getBlockZ());
        ps.setFloat(i++, holoLoc.getYaw());
        ps.setFloat(i++, holoLoc.getPitch());

        ps.setString(i++, chest.getHolographicTimerId().toString());
        setNullableString(ps, i++, chest.getHolographicStatusId());
        ps.setString(i++, chest.getHolographicOwnerId().toString());
        setNullableString(ps, i++, chest.getKillerUUID());
        ps.setString(i++, chest.getWorldName());
        ps.setInt(i++, chest.getXpStored());
        ps.setBytes(i++, ItemBytes.toBytesList(chest.getInventory()));

        bindIntegrity(ps, i, chest);
    }

    /**
     * Binds every mutable column of a chest.
     *
     * @return index of the first free placeholder
     */
    private static int bindUpdate(PreparedStatement ps, ChestData chest) throws SQLException {
        Location holoLoc = chest.getHolographicTimer();

        int i = 1;
        ps.setLong(i++, chest.getChestDate().getTime());
        ps.setBoolean(i++, chest.isInfinity());
        ps.setBoolean(i++, chest.isRemovedBlock());

        ps.setString(i++, holoLoc.getWorld().getName());
        ps.setInt(i++, holoLoc.getBlockX());
        ps.setInt(i++, holoLoc.getBlockY());
        ps.setInt(i++, holoLoc.getBlockZ());
        ps.setFloat(i++, holoLoc.getYaw());
        ps.setFloat(i++, holoLoc.getPitch());

        ps.setString(i++, chest.getHolographicTimerId().toString());
        setNullableString(ps, i++, chest.getHolographicStatusId());
        ps.setString(i++, chest.getHolographicOwnerId().toString());
        setNullableString(ps, i++, chest.getKillerUUID());
        ps.setString(i++, chest.getWorldName());
        ps.setInt(i++, chest.getXpStored());
        ps.setBytes(i++, ItemBytes.toBytesList(chest.getInventory()));

        return bindIntegrity(ps, i, chest);
    }

    private static int bindIntegrity(PreparedStatement ps, int index, ChestData chest) throws SQLException {
        int i = index;
        setNullableString(ps, i++, chest.getDeathId());
        ps.setString(i++, chest.getIntegrityState().name());
        ps.setLong(i++, chest.getIntegritySequence());
        setNullableString(ps, i++, chest.getIntegrityOwner());
        return i;
    }

    /**
     * Binds the {@link #IDENTITY_PREDICATE} parameters.
     *
     * @return index of the first free placeholder
     */
    private static int bindIdentity(PreparedStatement ps, int index, ChestData chest) throws SQLException {
        Location chestLoc = chest.getChestLocation();
        int i = index;
        setNullableString(ps, i++, chest.getDeathId());
        ps.setString(i++, chest.getPlayerStringUUID());
        ps.setString(i++, chestLoc.getWorld().getName());
        ps.setInt(i++, chestLoc.getBlockX());
        ps.setInt(i++, chestLoc.getBlockY());
        ps.setInt(i++, chestLoc.getBlockZ());
        return i;
    }

    /**
     * Binds the {@link #SLOT_PREDICATE} parameters.
     *
     * @return index of the first free placeholder
     */
    private static int bindSlot(PreparedStatement ps, int index, ChestData chest) throws SQLException {
        Location chestLoc = chest.getChestLocation();
        int i = index;
        ps.setString(i++, chest.getPlayerStringUUID());
        ps.setString(i++, chestLoc.getWorld().getName());
        ps.setInt(i++, chestLoc.getBlockX());
        ps.setInt(i++, chestLoc.getBlockY());
        ps.setInt(i++, chestLoc.getBlockZ());
        return i;
    }

    private static void setNullableString(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.toString());
        }
    }

    private static UUID toUuid(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Brings an existing table up to the current schema.
     * <p>
     * Works on the statement opened by {@link #initTable(Runnable)} instead of
     * asking for its own connection: {@link SQLite#connection()} hands out one
     * shared connection, so closing it here would also close the caller's
     * statement and silently kill the rest of the initialization.
     *
     * @param st statement owned by the caller
     */
    private static void migrateSchema(Statement st) throws SQLException {
        final List<String> columns = new ArrayList<>();

        try (ResultSet rs = st.executeQuery("PRAGMA index_info('idx_chest_location')")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }

        final boolean hasCorrectIndex =
                columns.size() == 4 &&
                        columns.get(0).equals("chest_world") &&
                        columns.get(1).equals("chest_x") &&
                        columns.get(2).equals("chest_y") &&
                        columns.get(3).equals("chest_z");

        if (!hasCorrectIndex) {
            st.execute("DROP INDEX IF EXISTS idx_chest_location");
            st.execute("CREATE INDEX idx_chest_location ON chest_data (chest_world, chest_x, chest_y, chest_z)");
        }

        final List<String> tableColumns = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info('chest_data')")) {
            while (rs.next()) {
                tableColumns.add(rs.getString("name"));
            }
        }

        if (!tableColumns.contains("killer_uuid")) {
            st.executeUpdate("ALTER TABLE chest_data ADD COLUMN killer_uuid TEXT");
        }
        if (!tableColumns.contains("holographic_status_id")) {
            st.executeUpdate("ALTER TABLE chest_data ADD COLUMN holographic_status_id TEXT");
        }
        if (!tableColumns.contains("death_id")) {
            st.executeUpdate("ALTER TABLE chest_data ADD COLUMN death_id TEXT");
        }
        if (!tableColumns.contains("integrity_state")) {
            // Chests written before the crash-consistency handshake existed are
            // adopted as confirmed: their death is long persisted on the player
            // side, and voiding them on upgrade would delete legitimate loot.
            st.executeUpdate("ALTER TABLE chest_data ADD COLUMN integrity_state TEXT NOT NULL DEFAULT 'CONFIRMED'");
        }
        if (!tableColumns.contains("integrity_seq")) {
            st.executeUpdate("ALTER TABLE chest_data ADD COLUMN integrity_seq BIGINT NOT NULL DEFAULT 0");
        }
        if (!tableColumns.contains("integrity_owner")) {
            st.executeUpdate("ALTER TABLE chest_data ADD COLUMN integrity_owner TEXT");
        }
    }

}
