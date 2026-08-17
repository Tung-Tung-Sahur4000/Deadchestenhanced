package me.crylonz.deadchest.compass;

import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.drops.LockedDropService;
import me.crylonz.deadchest.drops.LockedDropSite;
import me.crylonz.deadchest.utils.ConfigKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static me.crylonz.deadchest.DeadChestLoader.config;
import static me.crylonz.deadchest.DeadChestLoader.local;

/**
 * Hands out and maintains the compass pointing at a player's latest deadchest.
 * <p>
 * The compass is a plugin item, not loot: it is given on respawn, retargeted at
 * a fixed interval, cannot leave the inventory it was given in, and disappears
 * as soon as the player has no chest left to walk back to.
 * <p>
 * When the vanilla drop mode replaces the chests, the destination is the place
 * where the reserved drops are waiting instead, so the compass keeps working with
 * no chest at all.
 */
public final class GraveCompassService {

    private static final String COMPASS_TAG = "deadchest:grave_compass";
    private static final String COMPASS_TARGET_TAG = "deadchest:grave_compass_target";
    private static final int DEFAULT_UPDATE_SECONDS = 5;

    private static volatile NamespacedKey compassKey;
    private static volatile NamespacedKey compassTargetKey;

    private GraveCompassService() {
    }

    /**
     * @return {@code true} when players get a compass on respawn
     */
    public static boolean isEnabled() {
        return config != null && config.getBoolean(ConfigKey.RESPAWN_COMPASS);
    }

    /**
     * @return delay in ticks between two retargets of the compass
     */
    public static long updateIntervalTicks() {
        final int seconds = config == null ? DEFAULT_UPDATE_SECONDS : config.getInt(ConfigKey.RESPAWN_COMPASS_UPDATE_SECONDS);
        return Math.max(1, Math.min(seconds <= 0 ? DEFAULT_UPDATE_SECONDS : seconds, 3600)) * 20L;
    }

    /**
     * Gives the compass to a player who just respawned, if they have a chest to
     * find. A player who already carries one is only retargeted.
     *
     * @param player respawned player
     */
    public static void giveOnRespawn(final Player player) {
        if (!isEnabled() || player == null) {
            return;
        }

        final GraveCompassTarget target = latestTarget(player);
        if (target == null) {
            return;
        }

        if (findCompassSlot(player) != -1) {
            refresh(player);
            return;
        }

        final ItemStack compass = createCompass(target);
        if (compass == null) {
            // No persistent data on this server version: an untagged compass could
            // not be recognized later, so it would end up dropped or stored in a
            // grave like any other item. Better not to hand one out at all.
            return;
        }

        final Collection<ItemStack> overflow = player.getInventory().addItem(compass).values();
        for (ItemStack leftover : overflow) {
            // No room: dropping it would break the "cannot leave the inventory"
            // rule, so the compass is simply not given this time.
            if (leftover != null) {
                return;
            }
        }

        pointVanillaCompass(player, target.location());
        player.sendMessage(local.prefixed("compass.received"));
    }

    /**
     * Retargets the compass of a player at their latest chest, or removes it when
     * every chest has been collected.
     *
     * @param player player to update
     */
    public static void refresh(final Player player) {
        if (player == null) {
            return;
        }

        final int slot = findCompassSlot(player);
        final GraveCompassTarget target = latestTarget(player);

        if (target == null) {
            if (slot != -1) {
                removeAll(player);
            }
            return;
        }

        if (slot == -1) {
            return;
        }

        // The item is only rewritten when it points somewhere else, so a player
        // holding the compass does not see it replaced on every update.
        final ItemStack retargeted = alreadyTargets(player.getInventory().getItem(slot), target) ? null : createCompass(target);
        if (retargeted != null) {
            player.getInventory().setItem(slot, retargeted);
        }
        pointVanillaCompass(player, target.location());
    }

    /**
     * @param compass compass currently held
     * @param target  destination it should point at
     * @return {@code true} when the compass is already up to date
     */
    private static boolean alreadyTargets(final ItemStack compass, final GraveCompassTarget target) {
        if (compass == null || !compass.hasItemMeta() || target.id() == null) {
            return false;
        }
        try {
            final ItemMeta meta = compass.getItemMeta();
            final String storedTarget = meta == null ? null : meta.getPersistentDataContainer().get(targetKey(), PersistentDataType.STRING);
            return target.id().equals(storedTarget);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Retargets every online player, called by the update task.
     */
    public static void refreshAll() {
        if (!isEnabled()) {
            return;
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            DeadChestLoader.getSchedulerAdapter().executeForEntity(online, () -> refresh(online));
        }
    }

    /**
     * Removes every grave compass from a player inventory.
     *
     * @param player player to clean
     * @return number of removed compasses
     */
    public static int removeAll(final Player player) {
        if (player == null) {
            return 0;
        }
        return removeAll(player.getInventory());
    }

    /**
     * Removes every grave compass from an inventory.
     *
     * @param inventory inventory to clean
     * @return number of removed compasses
     */
    public static int removeAll(final Inventory inventory) {
        if (inventory == null) {
            return 0;
        }

        int removed = 0;
        final ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isGraveCompass(contents[slot])) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes every grave compass from a drop list.
     *
     * @param drops death drops
     * @return number of removed compasses
     */
    public static int removeFromDrops(final List<ItemStack> drops) {
        if (drops == null) {
            return 0;
        }

        int removed = 0;
        for (int index = drops.size() - 1; index >= 0; index--) {
            if (isGraveCompass(drops.get(index))) {
                drops.remove(index);
                removed++;
            }
        }
        return removed;
    }

    /**
     * @param item item to test
     * @return {@code true} when the item is a compass handed out by the plugin
     */
    public static boolean isGraveCompass(@Nullable final ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }

        try {
            final ItemMeta meta = item.getItemMeta();
            return meta != null && meta.getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * @param player player to inspect
     * @return the chest the compass must point at, or {@code null} when the
     * player has none
     */
    @Nullable
    public static ChestData latestChest(final Player player) {
        if (player == null) {
            return null;
        }

        final List<ChestData> chests = new ArrayList<>();
        DeadChestLoader.getChestDataCache().getPlayerLinkedDeadChestData(player, chests::add);

        ChestData latest = null;
        for (ChestData chest : chests) {
            if (chest == null || chest.getChestDate() == null || chest.getChestLocation() == null) {
                continue;
            }
            // A chest waiting for a crash reconciliation may be cancelled, sending
            // the player to a position that is about to disappear.
            if (!chest.isSettled()) {
                continue;
            }
            if (latest == null || chest.getChestDate().after(latest.getChestDate())) {
                latest = chest;
            }
        }
        return latest;
    }

    /**
     * Newest place this player has to walk back to : a DeadChest, or the reserved
     * drops of the vanilla drop mode. Both are considered, so a server that just
     * switched modes still points at whichever came last.
     *
     * @param player player to inspect
     * @return destination of the compass, or {@code null} when there is nothing left
     */
    @Nullable
    public static GraveCompassTarget latestTarget(final Player player) {
        if (player == null) {
            return null;
        }

        GraveCompassTarget target = GraveCompassTarget.ofChest(latestChest(player));

        final LockedDropSite site = LockedDropService.latestDropSite(player);
        final GraveCompassTarget dropTarget = GraveCompassTarget.ofDropSite(site);
        if (dropTarget != null && (target == null || dropTarget.timeMillis() > target.timeMillis())) {
            target = dropTarget;
        }

        return target;
    }

    /**
     * Builds the compass item targeting a chest.
     *
     * @param target chest to point at
     * @return tagged compass, or {@code null} when this server cannot carry the
     * tag that identifies it as a plugin item
     */
    @Nullable
    public static ItemStack createCompass(final ChestData target) {
        return createCompass(GraveCompassTarget.ofChest(target));
    }

    /**
     * Builds the compass item targeting a chest or a reserved drop site.
     *
     * @param target destination to point at
     * @return tagged compass, or {@code null} when this server cannot carry the
     * tag that identifies it as a plugin item
     */
    @Nullable
    public static ItemStack createCompass(final GraveCompassTarget target) {
        if (target == null) {
            return null;
        }

        final ItemStack compass = new ItemStack(Material.COMPASS, 1);
        final ItemMeta meta = compass.getItemMeta();
        if (meta == null) {
            return null;
        }

        meta.setDisplayName(local.get("compass.name"));

        final Location location = target.location();
        final List<String> lore = new ArrayList<>();
        lore.add(local.format("compass.lore",
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                target.worldName()));
        meta.setLore(lore);

        if (!tag(meta, target)) {
            return null;
        }
        applyLodestone(meta, location);

        compass.setItemMeta(meta);
        return compass;
    }

    /**
     * Marks the item as a deadchest compass and records what it points at.
     *
     * @return {@code false} when the server has no persistent data container,
     * which is the case before Minecraft 1.14
     */
    private static boolean tag(final ItemMeta meta, final GraveCompassTarget target) {
        try {
            meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte) 1);
            if (target.id() != null) {
                meta.getPersistentDataContainer().set(targetKey(), PersistentDataType.STRING, target.id());
            }
            return true;
        } catch (Throwable t) {
            DeadChestLoader.log.warning("[DeadChest] This server cannot tag items with persistent data, "
                    + "the deadchest compass is disabled : " + t);
            return false;
        }
    }

    /**
     * Points the item itself at the chest, which is what makes it work across
     * dimensions on the versions that support it.
     */
    private static void applyLodestone(final ItemMeta meta, final Location location) {
        try {
            if (meta instanceof CompassMeta) {
                final CompassMeta compassMeta = (CompassMeta) meta;
                compassMeta.setLodestone(location);
                compassMeta.setLodestoneTracked(false);
            }
        } catch (Throwable ignored) {
            // Older servers have no lodestone compass, the vanilla target below
            // still spins the needle in the same world.
        }
    }

    private static void pointVanillaCompass(final Player player, final Location location) {
        try {
            if (location != null && location.getWorld() != null && location.getWorld().equals(player.getWorld())) {
                player.setCompassTarget(location);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * @return slot of the first grave compass, or {@code -1}
     */
    private static int findCompassSlot(final Player player) {
        final ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isGraveCompass(contents[slot])) {
                return slot;
            }
        }
        return -1;
    }

    private static NamespacedKey key() {
        NamespacedKey cached = compassKey;
        if (cached == null) {
            cached = NamespacedKey.fromString(COMPASS_TAG);
            compassKey = cached;
        }
        return cached;
    }

    private static NamespacedKey targetKey() {
        NamespacedKey cached = compassTargetKey;
        if (cached == null) {
            cached = NamespacedKey.fromString(COMPASS_TARGET_TAG);
            compassTargetKey = cached;
        }
        return cached;
    }
}
