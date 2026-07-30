package me.crylonz.deadchest.drops;

import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Permission;
import me.crylonz.deadchest.placement.GraveBlocks;
import me.crylonz.deadchest.utils.ConfigKey;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static me.crylonz.deadchest.DeadChestLoader.config;
import static me.crylonz.deadchest.DeadChestLoader.local;
import static me.crylonz.deadchest.DeadChestLoader.log;
import static me.crylonz.deadchest.DeadChestLoader.plugin;
import static me.crylonz.deadchest.utils.Utils.generateLog;
import static me.crylonz.deadchest.utils.Utils.isIgnoredItem;

/**
 * Vanilla drop mode : DeadChest generates no chest at all and lets the items
 * fall on the ground like vanilla Minecraft, but the drops stay reserved for the
 * player who died and are protected from the vanilla despawn timer.
 * <p>
 * A reserved drop is removed once {@code vanilla-drop.despawn-seconds} elapsed,
 * counted in real time so unloaded chunks or an absent player do not freeze the
 * countdown.
 */
public final class LockedDropService {

    static final String OWNER_METADATA_KEY = "deadchest-drop-owner";
    static final String OWNER_NAME_METADATA_KEY = "deadchest-drop-owner-name";
    static final String EXPIRATION_METADATA_KEY = "deadchest-drop-expiration";

    private static final Map<UUID, LockedDrop> trackedDrops = new ConcurrentHashMap<>();
    private static DropTagStorage tagStorage;

    private LockedDropService() {
    }

    /**
     * @return {@code true} when DeadChest must stay out of the way and only lock vanilla drops
     */
    public static boolean isVanillaDropModeEnabled() {
        return isEnabled(ConfigKey.VANILLA_DROP_ENABLED);
    }

    public static boolean isOwnerOnlyPickup() {
        return isEnabled(ConfigKey.VANILLA_DROP_OWNER_ONLY_PICKUP);
    }

    public static boolean isDespawnProtectionEnabled() {
        return isEnabled(ConfigKey.VANILLA_DROP_PROTECT_FROM_DESPAWN);
    }

    /**
     * @return lifetime of a reserved drop in seconds, 0 when it never expires
     */
    public static int getDespawnSeconds() {
        if (config == null) {
            return 0;
        }
        return Math.max(0, config.getInt(ConfigKey.VANILLA_DROP_DESPAWN_SECONDS));
    }

    /**
     * Replaces the DeadChest generation by locked vanilla drops.
     *
     * @param event  death event whose drops are taken over
     * @param player player who died
     */
    public static void handlePlayerDeath(PlayerDeathEvent event, Player player) {
        if (event == null || player == null) {
            return;
        }

        final World world = player.getWorld();
        if (world == null) {
            return;
        }

        final List<ItemStack> stacksToLock = takeOverDrops(event);
        if (stacksToLock.isEmpty()) {
            generateLog("Player [" + player.getName() + "] died with nothing to lock : vanilla drop mode did nothing");
            return;
        }

        final Location dropLocation = resolveDropLocation(world, player.getLocation());
        final long expirationTime = computeExpirationTime(System.currentTimeMillis());

        for (ItemStack stack : stacksToLock) {
            lockDrop(world.dropItemNaturally(dropLocation, stack), player.getUniqueId(), player.getName(), expirationTime);
        }

        sendDeathPosition(player, dropLocation);

        generateLog("Locked " + stacksToLock.size() + " vanilla drop(s) for [" + player.getName() + "] in " + world.getName() +
                " at X:" + dropLocation.getBlockX() + " Y:" + dropLocation.getBlockY() + " Z:" + dropLocation.getBlockZ());

        if (isEnabled(ConfigKey.LOG_DEADCHEST_ON_CONSOLE) && log != null) {
            log.info("Locked vanilla drops for [" + player.getName() + "] at X:" + dropLocation.getBlockX() +
                    " Y:" + dropLocation.getBlockY() + " Z:" + dropLocation.getBlockZ());
        }
    }

    /**
     * Marks a freshly spawned item as reserved and starts tracking it.
     *
     * @param item           dropped item entity
     * @param ownerId        player allowed to pick it up
     * @param ownerName      owner name, kept for logs and messages
     * @param expirationTime epoch milliseconds of removal, 0 for no expiration
     */
    public static void lockDrop(Item item, UUID ownerId, String ownerName, long expirationTime) {
        if (item == null || ownerId == null) {
            return;
        }

        writeTags(item, ownerId, ownerName, expirationTime);
        applyEntityProtections(item);

        trackedDrops.put(item.getUniqueId(), new LockedDrop(item.getUniqueId(), ownerId, item.getLocation(), expirationTime));
    }

    /**
     * @return owner of the reserved drop, {@code null} when the item is a regular drop
     */
    public static UUID getOwner(Item item) {
        if (item == null) {
            return null;
        }

        String metadata = readMetadata(item, OWNER_METADATA_KEY);
        if (metadata != null) {
            try {
                return UUID.fromString(metadata);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        return storage().readOwner(item);
    }

    public static long getExpirationTime(Item item) {
        String metadata = readMetadata(item, EXPIRATION_METADATA_KEY);
        if (metadata != null) {
            try {
                return Long.parseLong(metadata);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }

        return storage().readExpiration(item);
    }

    public static boolean isLocked(Item item) {
        return getOwner(item) != null;
    }

    /**
     * Reserved drops stay locked even after the mode is turned off, so items
     * already on the ground keep behaving the way they were created.
     *
     * @param player player trying to pick the item up
     * @param item   reserved drop
     * @return {@code true} when the pickup is allowed
     */
    public static boolean canPickUp(Player player, Item item) {
        UUID ownerId = getOwner(item);
        if (ownerId == null || !isOwnerOnlyPickup()) {
            return true;
        }
        if (player == null) {
            return false;
        }

        return ownerId.equals(player.getUniqueId())
                || player.hasPermission(Permission.DROP_PASS.label)
                || player.hasPermission(Permission.CHESTPASS.label);
    }

    public static boolean isExpired(Item item, long now) {
        long expirationTime = getExpirationTime(item);
        return expirationTime > 0L && expirationTime <= now;
    }

    /**
     * Periodic maintenance : removes expired drops and keeps the vanilla despawn
     * timer of the remaining ones from ever completing.
     */
    public static void tick() {
        if (trackedDrops.isEmpty()) {
            return;
        }

        final long now = System.currentTimeMillis();
        for (final LockedDrop drop : new ArrayList<>(trackedDrops.values())) {
            final Location location = drop.getLocation();
            if (location == null) {
                trackedDrops.remove(drop.getItemId());
                continue;
            }

            DeadChestLoader.getSchedulerAdapter().executeAtLocation(location, () -> processTrackedDrop(drop, now));
        }
    }

    /**
     * Re-registers the reserved drops of a chunk that just came back, so locks
     * and timers survive a chunk unload or a server restart.
     *
     * @param chunk chunk being loaded
     */
    public static void trackLoadedDrops(Chunk chunk) {
        if (chunk == null) {
            return;
        }

        Entity[] entities;
        try {
            entities = chunk.getEntities();
        } catch (Throwable ignored) {
            return;
        }

        for (Entity entity : entities) {
            if (entity instanceof Item) {
                trackExistingDrop((Item) entity);
            }
        }
    }

    /**
     * Registers an already tagged item, typically found back after a restart.
     *
     * @param item item entity to inspect
     */
    public static void trackExistingDrop(Item item) {
        if (item == null || trackedDrops.containsKey(item.getUniqueId())) {
            return;
        }

        UUID ownerId = getOwner(item);
        if (ownerId == null) {
            return;
        }

        long expirationTime = getExpirationTime(item);

        applyEntityProtections(item);
        trackedDrops.put(item.getUniqueId(), new LockedDrop(item.getUniqueId(), ownerId, item.getLocation(), expirationTime));
    }

    /**
     * Scans the chunks already loaded at startup for reserved drops left by a
     * previous session.
     */
    public static void trackDropsOfLoadedChunks() {
        try {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    trackLoadedDrops(chunk);
                }
            }
        } catch (Throwable ignored) {
            // Platform without a global chunk view : chunk load events take over.
        }
    }

    public static int getTrackedDropAmount() {
        return trackedDrops.size();
    }

    public static void clearTracking() {
        trackedDrops.clear();
    }

    /**
     * @param now current epoch milliseconds
     * @return epoch milliseconds of expiration, 0 when drops never expire
     */
    static long computeExpirationTime(long now) {
        final int despawnSeconds = getDespawnSeconds();
        return despawnSeconds <= 0 ? 0L : now + despawnSeconds * 1000L;
    }

    /**
     * Takes the drops out of the death event so they can be spawned back as
     * reserved items. Ignored items are left untouched for vanilla or another
     * plugin to handle.
     *
     * @param event death event
     * @return stacks the plugin takes over
     */
    private static List<ItemStack> takeOverDrops(PlayerDeathEvent event) {
        final List<ItemStack> stacksToLock = new ArrayList<>();
        final Iterator<ItemStack> iterator = event.getDrops().iterator();

        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            if (stack == null || stack.getType().isAir() || isIgnoredItem(stack)) {
                continue;
            }
            stacksToLock.add(stack);
            iterator.remove();
        }

        return stacksToLock;
    }

    private static void processTrackedDrop(LockedDrop drop, long now) {
        final Location location = drop.getLocation();
        final World world = drop.getWorld();
        if (location == null || world == null) {
            trackedDrops.remove(drop.getItemId());
            return;
        }

        final boolean chunkLoaded = isChunkLoaded(world, location);
        final Item item = resolveItem(drop, world, location, chunkLoaded);
        if (item == null || !item.isValid()) {
            // A drop inside an unloaded chunk is only sleeping on disk : it is
            // handled again as soon as its chunk comes back.
            if (chunkLoaded) {
                trackedDrops.remove(drop.getItemId());
            }
            return;
        }

        drop.updateLocation(item.getLocation());

        if (drop.isExpired(now)) {
            clearMetadata(item);
            item.remove();
            trackedDrops.remove(drop.getItemId());
            return;
        }

        if (isDespawnProtectionEnabled()) {
            refreshDespawnTimer(item);
        }
    }

    private static boolean isChunkLoaded(World world, Location location) {
        try {
            return world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Looks the item entity up without ever forcing a chunk to load.
     *
     * @param chunkLoaded whether the chunk holding the last known position is loaded
     * @return the item entity, or {@code null} when it is gone or still asleep on disk
     */
    private static Item resolveItem(LockedDrop drop, World world, Location location, boolean chunkLoaded) {
        try {
            Entity entity = Bukkit.getEntity(drop.getItemId());
            if (entity instanceof Item) {
                return (Item) entity;
            }
        } catch (Throwable ignored) {
            // Fall back to a chunk lookup on platforms restricting global entity access.
        }

        if (!chunkLoaded) {
            return null;
        }

        try {
            for (Entity entity : world.getChunkAt(location).getEntities()) {
                if (entity instanceof Item && drop.getItemId().equals(entity.getUniqueId())) {
                    return (Item) entity;
                }
            }
        } catch (Throwable ignored) {
            // Chunk not readable from this thread : retried on the next tick.
        }

        return null;
    }

    /**
     * Resets the vanilla age of the item so its 5 minutes despawn timer never
     * completes while the drop is still reserved.
     *
     * @param item reserved drop
     */
    static void refreshDespawnTimer(Item item) {
        try {
            item.setTicksLived(1);
        } catch (Throwable ignored) {
            // Not supported by this platform : the configured lifetime still applies.
        }
    }

    private static void applyEntityProtections(Item item) {
        try {
            item.setPersistent(true);
        } catch (Throwable ignored) {
            // Optional hardening only.
        }

        if (isEnabled(ConfigKey.VANILLA_DROP_INVULNERABLE)) {
            try {
                item.setInvulnerable(true);
            } catch (Throwable ignored) {
                // Optional hardening only.
            }
        }

        if (isEnabled(ConfigKey.VANILLA_DROP_GLOW)) {
            try {
                item.setGlowing(true);
            } catch (Throwable ignored) {
                // Optional hardening only.
            }
        }

        if (isDespawnProtectionEnabled()) {
            refreshDespawnTimer(item);
        }
    }

    private static void sendDeathPosition(Player player, Location dropLocation) {
        if (!isEnabled(ConfigKey.DISPLAY_POSITION_ON_DEATH) || local == null) {
            return;
        }

        player.sendMessage(local.prefixed("death.drop-position",
                dropLocation.getBlockX(), dropLocation.getBlockY(), dropLocation.getBlockZ()));

        if (!isOwnerOnlyPickup()) {
            return;
        }

        final int despawnSeconds = getDespawnSeconds();
        if (despawnSeconds <= 0) {
            player.sendMessage(local.prefixed("death.drop-locked-forever"));
        } else {
            player.sendMessage(local.prefixed("death.drop-locked", despawnSeconds / 60, despawnSeconds % 60));
        }
    }

    /**
     * Keeps the drops inside the world instead of letting them fall in the void
     * when the player dies below the world.
     *
     * @param world world of the death
     * @param location death position
     * @return position where the reserved drops are spawned
     */
    private static Location resolveDropLocation(World world, Location location) {
        final Location dropLocation = location.clone();
        final int minHeight = GraveBlocks.minHeight(world);

        if (dropLocation.getY() < minHeight) {
            int highestBlock = world.getHighestBlockYAt(dropLocation.getBlockX(), dropLocation.getBlockZ()) + 1;
            dropLocation.setY(Math.max(minHeight, highestBlock));
        }

        final int maxHeight = GraveBlocks.maxHeight(world);
        if (dropLocation.getY() > maxHeight) {
            dropLocation.setY(maxHeight);
        }

        return dropLocation;
    }

    /**
     * Writes the lock on the entity. Server metadata is only used when the
     * persistent data API is missing, to avoid keeping tags in memory for every
     * item ever dropped.
     */
    private static void writeTags(Item item, UUID ownerId, String ownerName, long expirationTime) {
        final DropTagStorage storage = storage();
        storage.write(item, ownerId, ownerName, expirationTime);

        if (!storage.isPersistent()) {
            writeMetadata(item, ownerId, ownerName, expirationTime);
        }
    }

    private static void clearMetadata(Item item) {
        if (plugin == null) {
            return;
        }

        try {
            item.removeMetadata(OWNER_METADATA_KEY, plugin);
            item.removeMetadata(OWNER_NAME_METADATA_KEY, plugin);
            item.removeMetadata(EXPIRATION_METADATA_KEY, plugin);
        } catch (Throwable ignored) {
            // Nothing else to clean up.
        }
    }

    private static void writeMetadata(Item item, UUID ownerId, String ownerName, long expirationTime) {
        if (plugin == null) {
            return;
        }

        try {
            item.setMetadata(OWNER_METADATA_KEY, new FixedMetadataValue(plugin, ownerId.toString()));
            item.setMetadata(EXPIRATION_METADATA_KEY, new FixedMetadataValue(plugin, String.valueOf(expirationTime)));
            if (ownerName != null) {
                item.setMetadata(OWNER_NAME_METADATA_KEY, new FixedMetadataValue(plugin, ownerName));
            }
        } catch (Throwable ignored) {
            // Persistent tags remain the source of truth.
        }
    }

    private static String readMetadata(Item item, String key) {
        try {
            if (!item.hasMetadata(key)) {
                return null;
            }

            List<MetadataValue> values = item.getMetadata(key);
            if (values == null || values.isEmpty()) {
                return null;
            }

            String value = values.get(0).asString();
            return value == null || value.isEmpty() ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isEnabled(ConfigKey key) {
        return config != null && Boolean.TRUE.equals(config.getBoolean(key));
    }

    private static DropTagStorage storage() {
        if (tagStorage == null) {
            tagStorage = DropTagStorage.create();
        }
        return tagStorage;
    }
}
