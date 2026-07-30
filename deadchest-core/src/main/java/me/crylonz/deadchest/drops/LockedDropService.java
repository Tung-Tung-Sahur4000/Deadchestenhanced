package me.crylonz.deadchest.drops;

import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Permission;
import me.crylonz.deadchest.integrity.ChestIntegrityService;
import me.crylonz.deadchest.integrity.PlayerDataStamp;
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
import java.util.Arrays;
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
    static final String CREATION_METADATA_KEY = "deadchest-drop-created";
    static final String EXPIRATION_METADATA_KEY = "deadchest-drop-expiration";
    static final String SEQUENCE_METADATA_KEY = "deadchest-drop-sequence";
    static final String CONFIRMED_METADATA_KEY = "deadchest-drop-confirmed";

    private static final Map<UUID, LockedDrop> trackedDrops = new ConcurrentHashMap<>();

    /**
     * Sequence the player data of an owner was proven to carry at their last
     * login. A drop stamped above that number was created by a death the server
     * never saved, so it is a rollback duplicate. Kept per session because a drop
     * sleeping in an unloaded chunk can only be judged once its chunk comes back.
     */
    private static final Map<UUID, Long> persistedSequences = new ConcurrentHashMap<>();
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
        final long creationTime = System.currentTimeMillis();
        final long expirationTime = computeExpirationTime(creationTime);

        // Crash protection : the death is stamped on the player data before the
        // items leave the inventory, so a server killed before that file is
        // written can be told apart from a death that really happened.
        final long sequence = ChestIntegrityService.isEnabled()
                ? ChestIntegrityService.allocateSequence(player)
                : 0L;

        if (ChestIntegrityService.isEnabled() && sequence == 0L) {
            // No stamp means no login will ever be able to prove this death was
            // saved, so the drops keep the historic unprotected behavior.
            generateLog("Could not stamp [" + player.getName()
                    + "] : reserved drops created without crash duplication protection.");
        }

        for (ItemStack stack : stacksToLock) {
            Item spawned = world.dropItemNaturally(dropLocation, stack);
            lockDrop(spawned, player.getUniqueId(), player.getName(), creationTime, expirationTime);
            if (sequence > 0L) {
                stampPending(spawned, sequence);
            }
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
     * @param creationTime   epoch milliseconds of the death
     * @param expirationTime epoch milliseconds of removal, 0 for no expiration
     */
    public static void lockDrop(Item item, UUID ownerId, String ownerName, long creationTime, long expirationTime) {
        if (item == null || ownerId == null) {
            return;
        }

        writeTags(item, ownerId, ownerName, creationTime, expirationTime);
        applyEntityProtections(item);

        trackedDrops.put(item.getUniqueId(),
                new LockedDrop(item.getUniqueId(), ownerId, item.getLocation(), creationTime, expirationTime));
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

    /**
     * @return epoch milliseconds of the death that created this drop, 0 when unknown
     */
    public static long getCreationTime(Item item) {
        String metadata = readMetadata(item, CREATION_METADATA_KEY);
        if (metadata != null) {
            try {
                return Long.parseLong(metadata);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }

        return storage().readCreation(item);
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

    /**
     * @return sequence stamped on this drop, 0 when it carries no stamp
     */
    public static long getIntegritySequence(Item item) {
        String metadata = readMetadata(item, SEQUENCE_METADATA_KEY);
        if (metadata != null) {
            try {
                return Long.parseLong(metadata);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }

        return storage().readSequence(item);
    }

    /**
     * @return {@code false} while the death behind this drop is still waiting for
     * the player data to be written to disk
     */
    public static boolean isIntegrityConfirmed(Item item) {
        String metadata = readMetadata(item, CONFIRMED_METADATA_KEY);
        if (metadata != null) {
            return !"false".equals(metadata);
        }

        return storage().readConfirmed(item);
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

        try {
            trackLoadedDrops(Arrays.asList(chunk.getEntities()));
        } catch (Throwable ignored) {
            // Entities not readable yet : the entity load event takes over.
        }
    }

    /**
     * Re-registers the reserved drops of a batch of entities that just came back.
     *
     * @param entities entities being loaded
     */
    public static void trackLoadedDrops(Iterable<Entity> entities) {
        if (entities == null) {
            return;
        }

        for (Entity entity : entities) {
            if (entity instanceof Item) {
                trackExistingDrop((Item) entity);
            }
        }
    }

    /**
     * @return {@code true} when the platform loads entities through their own event,
     * which is the case on Paper since Minecraft 1.17
     */
    public static boolean supportsEntitiesLoadEvent() {
        try {
            Class.forName("org.bukkit.event.world.EntitiesLoadEvent");
            return true;
        } catch (Throwable ignored) {
            return false;
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

        long creationTime = getCreationTime(item);
        long expirationTime = getExpirationTime(item);

        applyEntityProtections(item);
        LockedDrop drop = new LockedDrop(item.getUniqueId(), ownerId, item.getLocation(), creationTime, expirationTime);
        drop.setIntegrity(getIntegritySequence(item), isIntegrityConfirmed(item));
        trackedDrops.put(item.getUniqueId(), drop);

        // A drop coming back from an unloaded chunk still has to be judged against
        // the player file read at the last login of its owner.
        Long persistedSequence = persistedSequences.get(ownerId);
        if (persistedSequence != null) {
            applyVerdict(drop, persistedSequence, Bukkit.getPlayer(ownerId));
        }
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

    /**
     * Marks a drop as created by a death whose player side is not on disk yet.
     *
     * @param item     reserved drop
     * @param sequence sequence stamped on the owner player data
     */
    static void stampPending(Item item, long sequence) {
        if (item == null || sequence <= 0L) {
            return;
        }

        storage().writeIntegrity(item, sequence, false);
        writeIntegrityMetadata(item, sequence, false);

        LockedDrop tracked = trackedDrops.get(item.getUniqueId());
        if (tracked != null) {
            tracked.setIntegrity(sequence, false);
        }
    }

    /**
     * Confirms the drops of a player whose post death state reached the disk.
     * Mirrors what the chests do on respawn and on quit.
     *
     * @param player owner of the drops
     * @return number of drops confirmed
     */
    public static int settleIntegrity(Player player) {
        if (player == null || trackedDrops.isEmpty()) {
            return 0;
        }

        int settled = 0;
        for (LockedDrop drop : trackedDrops.values()) {
            if (drop == null || drop.isConfirmed() || !player.getUniqueId().equals(drop.getOwnerId())) {
                continue;
            }

            drop.setIntegrity(drop.getIntegritySequence(), true);
            writeConfirmed(drop);
            settled++;
        }
        return settled;
    }

    /**
     * Judges the reserved drops of a reconnecting player against what their player
     * file actually kept.
     * <p>
     * A drop stamped above the sequence the player data carries was created by a
     * death the server never saved : the player came back with those items still
     * in the inventory, so the drops on the ground are a duplicate.
     *
     * @param player             reconnecting player
     * @param persistedSequence  sequence read back from the player data
     */
    public static void reconcileIntegrity(Player player, long persistedSequence) {
        if (player == null) {
            return;
        }

        persistedSequences.put(player.getUniqueId(), persistedSequence);

        for (LockedDrop drop : new ArrayList<>(trackedDrops.values())) {
            if (drop != null && player.getUniqueId().equals(drop.getOwnerId())) {
                applyVerdict(drop, persistedSequence, player);
            }
        }
    }

    /**
     * @param ownerId owner to look up
     * @return highest sequence stamped on the drops of that owner, so the chest
     * side keeps allocating sequences above them
     */
    public static long highestStampedSequence(UUID ownerId) {
        if (ownerId == null) {
            return 0L;
        }

        long highest = 0L;
        for (LockedDrop drop : trackedDrops.values()) {
            if (drop != null && ownerId.equals(drop.getOwnerId())) {
                highest = Math.max(highest, drop.getIntegritySequence());
            }
        }
        return highest;
    }

    /**
     * Applies the login verdict to a single drop : confirmed when the player data
     * carries its sequence, removed when the death behind it was rolled back.
     */
    private static void applyVerdict(LockedDrop drop, long persistedSequence, Player player) {
        if (drop.getIntegritySequence() <= 0L) {
            return;
        }

        if (drop.getIntegritySequence() <= persistedSequence) {
            if (!drop.isConfirmed()) {
                drop.setIntegrity(drop.getIntegritySequence(), true);
                writeConfirmed(drop);
            }
            return;
        }

        if (!ChestIntegrityService.shouldVoidRollbackDuplicates()) {
            generateLog("Reserved drop of [" + (player == null ? drop.getOwnerId() : player.getName())
                    + "] comes from a death the server never saved (server crash), but '"
                    + ConfigKey.INTEGRITY_ON_ROLLBACK + "' is set to keep : drop kept.");
            drop.setIntegrity(drop.getIntegritySequence(), true);
            writeConfirmed(drop);
            return;
        }

        removeRollbackDuplicate(drop, player);
    }

    /**
     * Removes a drop proven to hold items the player already owns again.
     */
    private static void removeRollbackDuplicate(LockedDrop drop, Player player) {
        final Location location = drop.getLocation();
        if (location == null) {
            trackedDrops.remove(drop.getItemId());
            return;
        }

        DeadChestLoader.getSchedulerAdapter().executeAtLocation(location, () -> {
            World world = drop.getWorld();
            if (world != null) {
                Item item = resolveItem(drop, world, location, isChunkLoaded(world, location));
                if (item != null) {
                    clearMetadata(item);
                    item.remove();
                }
            }
            trackedDrops.remove(drop.getItemId());
        });

        generateLog("Reserved drop of [" + (player == null ? String.valueOf(drop.getOwnerId()) : player.getName())
                + "] removed : the death was never saved on the player side (server crash), "
                + "its content is already back in the inventory.");

        if (player != null && local != null) {
            player.sendMessage(local.prefixed("death.drop-rollback-voided"));
        }
    }

    private static void writeConfirmed(LockedDrop drop) {
        final Location location = drop.getLocation();
        final World world = drop.getWorld();
        if (location == null || world == null) {
            return;
        }

        DeadChestLoader.getSchedulerAdapter().executeAtLocation(location, () -> {
            Item item = resolveItem(drop, world, location, isChunkLoaded(world, location));
            if (item != null) {
                storage().writeIntegrity(item, drop.getIntegritySequence(), true);
                writeIntegrityMetadata(item, drop.getIntegritySequence(), true);
            }
        });
    }

    /**
     * Latest place where this player left reserved drops, so the plugin can point
     * them back to it. A site disappears on its own once every drop of that death
     * has been picked up or expired.
     *
     * @param player owner of the drops
     * @return newest site of that player, or {@code null} when they have none
     */
    public static LockedDropSite latestDropSite(Player player) {
        if (player == null) {
            return null;
        }
        return latestDropSite(player.getUniqueId());
    }

    /**
     * @param ownerId owner of the drops
     * @return newest site of that owner, or {@code null} when they have none
     */
    public static LockedDropSite latestDropSite(UUID ownerId) {
        if (ownerId == null || trackedDrops.isEmpty()) {
            return null;
        }

        LockedDrop latest = null;
        for (LockedDrop drop : trackedDrops.values()) {
            if (drop == null || !ownerId.equals(drop.getOwnerId()) || drop.getLocation() == null) {
                continue;
            }
            if (latest == null || drop.getCreationTime() > latest.getCreationTime()) {
                latest = drop;
            }
        }

        return latest == null ? null : new LockedDropSite(ownerId, latest.getLocation(), latest.getCreationTime());
    }

    public static int getTrackedDropAmount() {
        return trackedDrops.size();
    }

    public static void clearTracking() {
        trackedDrops.clear();
        persistedSequences.clear();
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
    private static void writeTags(Item item, UUID ownerId, String ownerName, long creationTime, long expirationTime) {
        final DropTagStorage storage = storage();
        storage.write(item, ownerId, ownerName, creationTime, expirationTime);

        if (!storage.isPersistent()) {
            writeMetadata(item, ownerId, ownerName, creationTime, expirationTime);
        }
    }

    private static void writeIntegrityMetadata(Item item, long sequence, boolean confirmed) {
        if (plugin == null || storage().isPersistent()) {
            return;
        }

        try {
            item.setMetadata(SEQUENCE_METADATA_KEY, new FixedMetadataValue(plugin, String.valueOf(sequence)));
            item.setMetadata(CONFIRMED_METADATA_KEY, new FixedMetadataValue(plugin, String.valueOf(confirmed)));
        } catch (Throwable ignored) {
            // Persistent tags remain the source of truth.
        }
    }

    private static void clearMetadata(Item item) {
        if (plugin == null) {
            return;
        }

        try {
            item.removeMetadata(OWNER_METADATA_KEY, plugin);
            item.removeMetadata(OWNER_NAME_METADATA_KEY, plugin);
            item.removeMetadata(CREATION_METADATA_KEY, plugin);
            item.removeMetadata(SEQUENCE_METADATA_KEY, plugin);
            item.removeMetadata(CONFIRMED_METADATA_KEY, plugin);
            item.removeMetadata(EXPIRATION_METADATA_KEY, plugin);
        } catch (Throwable ignored) {
            // Nothing else to clean up.
        }
    }

    private static void writeMetadata(Item item, UUID ownerId, String ownerName, long creationTime, long expirationTime) {
        if (plugin == null) {
            return;
        }

        try {
            item.setMetadata(OWNER_METADATA_KEY, new FixedMetadataValue(plugin, ownerId.toString()));
            item.setMetadata(CREATION_METADATA_KEY, new FixedMetadataValue(plugin, String.valueOf(creationTime)));
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
