package me.crylonz.deadchest.placement;

import me.crylonz.deadchest.DeadChestLoader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Asks the rest of the server whether a player would be allowed to place a block
 * at a given position.
 * <p>
 * Protection plugins do not expose a common API, but they all answer the same
 * question through {@link BlockPlaceEvent}. A probe event is fired without
 * touching the world: if anything cancels it, the position is claimed by someone
 * else and the grave must go somewhere the owner can actually reach it.
 * <p>
 * The probe is flagged while it runs so the plugin ignores its own event
 * listeners, which would otherwise answer questions about a block that is not
 * being placed.
 */
public final class BuildAccessProbe {

    private static final ThreadLocal<Boolean> PROBING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BuildAccessProbe() {
    }

    /**
     * @return {@code true} while a probe event is being fired, meaning listeners
     * are looking at a block that is not really being placed
     */
    public static boolean isProbing() {
        return PROBING.get();
    }

    /**
     * Checks whether the player may build at that block.
     *
     * @param player player the grave belongs to
     * @param block  candidate block
     * @return {@code true} when the position is free to use
     */
    public static boolean canBuild(final Player player, final Block block) {
        if (player == null || block == null) {
            return false;
        }

        PROBING.set(Boolean.TRUE);
        try {
            final BlockPlaceEvent probe = new BlockPlaceEvent(
                    block,
                    block.getState(),
                    block.getRelative(0, -1, 0),
                    new ItemStack(Material.CHEST),
                    player,
                    true,
                    EquipmentSlot.HAND
            );

            Bukkit.getPluginManager().callEvent(probe);
            return !probe.isCancelled() && probe.canBuild();
        } catch (Throwable t) {
            // A broken listener must never stop a death from being handled: when
            // the answer cannot be obtained, the position is accepted and the
            // other placement checks still apply.
            DeadChestLoader.log.warning("[DeadChest] Build permission probe failed at "
                    + block.getX() + " " + block.getY() + " " + block.getZ() + " : " + t);
            return true;
        } finally {
            PROBING.set(Boolean.FALSE);
        }
    }
}
