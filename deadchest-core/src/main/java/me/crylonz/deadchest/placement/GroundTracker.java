package me.crylonz.deadchest.placement;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the last block a player safely stood on.
 * <p>
 * Several placement rules need it: a player who dies inside lava, inside a wall
 * or inside powder snow has no usable position of their own, and the last solid
 * ground they walked on is the closest thing to where they would expect their
 * grave.
 */
public final class GroundTracker {

    private static final Map<UUID, Location> lastSafeGround = new ConcurrentHashMap<>();

    private GroundTracker() {
    }

    /**
     * Records the position when the player stands on something solid and is not
     * currently inside a hazard.
     *
     * @param player player to sample
     */
    public static void track(final Player player) {
        if (player == null) {
            return;
        }

        final Location location = player.getLocation();
        final Block feet = location.getBlock();
        final Block below = feet.getRelative(0, -1, 0);

        if (!GraveBlocks.isSolidSupport(below)) {
            return;
        }
        if (GraveBlocks.isLava(feet) || GraveBlocks.isWater(feet) || GraveBlocks.isPowderSnow(feet)) {
            return;
        }
        if (!GraveBlocks.isReplaceable(feet)) {
            return;
        }

        lastSafeGround.put(player.getUniqueId(), GraveBlocks.blockLocation(location));
    }

    /**
     * @param player player to look up
     * @return last safe standing position, or {@code null} when unknown
     */
    public static Location lastSafeGround(final Player player) {
        if (player == null) {
            return null;
        }
        final Location tracked = lastSafeGround.get(player.getUniqueId());
        if (tracked == null || tracked.getWorld() == null) {
            return null;
        }
        // A position from another world would teleport the grave across dimensions.
        if (!tracked.getWorld().equals(player.getWorld())) {
            return null;
        }
        return tracked.clone();
    }

    /**
     * Drops the tracked position of a player, on quit or on world change.
     *
     * @param player player to forget
     */
    public static void forget(final Player player) {
        if (player != null) {
            lastSafeGround.remove(player.getUniqueId());
        }
    }

    /**
     * Test and reload helper.
     */
    public static void reset() {
        lastSafeGround.clear();
    }
}
