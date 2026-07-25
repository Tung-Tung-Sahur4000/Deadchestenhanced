package me.crylonz.deadchest.integrity;

import me.crylonz.deadchest.DeadChestLoader;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Reads and writes the DeadChest integrity stamp carried by the player itself.
 * <p>
 * The stamp is stored in the player {@link PersistentDataContainer}, which the
 * server serializes inside the very same {@code playerdata/<uuid>.dat} file as
 * the inventory. That co-location is the whole point: if a crash rolls the
 * player file back to a state where the death never happened, the stamp rolls
 * back with the items. Comparing the stamp with the sequence recorded on a chest
 * therefore tells us, after a restart, whether the player side of a transfer
 * actually made it to disk.
 */
public final class PlayerDataStamp {

    /**
     * Highest sequence number the player data is known to carry.
     */
    private static final String SEQUENCE_KEY = "deadchest:integrity_seq";

    private static volatile NamespacedKey sequenceKey;

    private PlayerDataStamp() {
    }

    /**
     * Reads the sequence stamped on a player.
     *
     * @param player player to read, may be {@code null}
     * @return stamped sequence, or {@code 0} when the player carries no stamp
     */
    public static long readSequence(Player player) {
        PersistentDataContainer container = container(player);
        if (container == null) {
            return 0L;
        }
        try {
            Long value = container.get(key(), PersistentDataType.LONG);
            return value == null ? 0L : value;
        } catch (RuntimeException e) {
            DeadChestLoader.log.warning("[DeadChest] Unable to read integrity stamp of " + player.getName() + " : " + e);
            return 0L;
        }
    }

    /**
     * Stamps a sequence on a player.
     * <p>
     * The value only becomes meaningful once the player data is written to disk,
     * which is what {@link #flush(Player)} forces.
     *
     * @param player   player to stamp
     * @param sequence sequence to write
     * @return {@code true} when the stamp was applied
     */
    public static boolean writeSequence(Player player, long sequence) {
        PersistentDataContainer container = container(player);
        if (container == null) {
            return false;
        }
        try {
            container.set(key(), PersistentDataType.LONG, sequence);
            return true;
        } catch (RuntimeException e) {
            DeadChestLoader.log.warning("[DeadChest] Unable to stamp " + player.getName() + " : " + e);
            return false;
        }
    }

    /**
     * Forces the server to write the player data (inventory + stamp) to disk.
     * <p>
     * Without this call the player file is only written on autosave, quit or
     * clean shutdown, which leaves a multi-minute window where a hard kill
     * duplicates every item a deadchest holds.
     *
     * @param player player to persist
     * @return {@code true} when the save was performed
     */
    public static boolean flush(Player player) {
        if (player == null) {
            return false;
        }
        try {
            player.saveData();
            return true;
        } catch (Throwable t) {
            // Older/alternative server implementations may not support an explicit
            // save. The handshake still works, it just relies on the next vanilla
            // save instead of an immediate one.
            DeadChestLoader.log.warning("[DeadChest] Could not flush player data of " + player.getName() + " : " + t);
            return false;
        }
    }

    private static PersistentDataContainer container(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return player.getPersistentDataContainer();
        } catch (Throwable t) {
            return null;
        }
    }

    private static NamespacedKey key() {
        NamespacedKey cached = sequenceKey;
        if (cached == null) {
            cached = NamespacedKey.fromString(SEQUENCE_KEY);
            sequenceKey = cached;
        }
        return cached;
    }
}
