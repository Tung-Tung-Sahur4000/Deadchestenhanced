package me.crylonz.deadchest;

import me.crylonz.deadchest.db.ChestDataRepository;
import me.crylonz.deadchest.integrity.ChestIntegrityState;
import org.bukkit.Location;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@SerializableAs("ChestData")
public final class ChestData {

    private List<ItemStack> inventory;
    private Location chestLocation;
    private String playerName;
    private UUID playerUUID;
    private Date chestDate;
    private boolean isInfinity;
    private boolean isRemovedBlock;
    private Location holographicTimer;
    private UUID holographicTimerId;
    private UUID holographicStatusId;
    private UUID holographicOwnerId;
    private UUID killerUUID;
    private String worldName;

    private int xpStored;

    /**
     * Stable identity of the chest, independent from its location.
     * Two chests may share a block position over time; the death id never
     * collides and is what database deletions target.
     */
    private UUID deathId = UUID.randomUUID();

    /**
     * Crash-consistency state, see {@link ChestIntegrityState}.
     */
    private ChestIntegrityState integrityState = ChestIntegrityState.CONFIRMED;

    /**
     * Sequence that must be present in {@link #integrityOwner}'s player data for
     * the current state transition to be considered durable on the player side.
     */
    private long integritySequence;

    /**
     * Player whose data file settles the current transition: the dead player for
     * {@link ChestIntegrityState#PENDING}, the looter for
     * {@link ChestIntegrityState#CLAIMED}.
     */
    private UUID integrityOwner;

    /**
     * Guards the content hand over so a chest can never be emptied twice
     * (double click, concurrent looters, expiration racing a pickup).
     */
    private final AtomicBoolean transferLock = new AtomicBoolean(false);

    public ChestData(ChestData chest) {
        this.inventory = chest.getInventory();
        this.chestLocation = chest.getChestLocation();
        this.playerName = chest.getPlayerName();
        this.playerUUID = chest.getPlayerUUID();
        this.chestDate = chest.getChestDate();
        this.isRemovedBlock = chest.isRemovedBlock();
        this.isInfinity = chest.isInfinity();
        this.holographicTimer = chest.getHolographicTimer();
        this.holographicTimerId = chest.getHolographicTimerId();
        this.holographicStatusId = chest.getHolographicStatusId();
        this.holographicOwnerId = chest.getHolographicOwnerId();
        this.killerUUID = chest.getKillerUUID();
        this.worldName = chest.getWorldName();
        this.xpStored = chest.getXpStored();
        this.deathId = chest.getDeathId();
        this.integrityState = chest.getIntegrityState();
        this.integritySequence = chest.getIntegritySequence();
        this.integrityOwner = chest.getIntegrityOwner();
    }

    public ChestData(final Inventory inv,
                     final Location chestLocation,
                     final Player p,
                     final boolean isInfinity,
                     final ArmorStand asTimer,
                     final ArmorStand owner,
                     final int xpToStore) {

        if (p != null) {
            this.inventory = Arrays.asList(inv.getContents());
            this.chestLocation = chestLocation.clone();
            this.playerName = p.getName();
            this.playerUUID = p.getUniqueId();
            this.chestDate = new Date();
            this.isInfinity = isInfinity;
            this.isRemovedBlock = false;
            this.holographicTimer = asTimer.getLocation().clone();
            this.holographicTimerId = asTimer.getUniqueId();
            this.holographicStatusId = null;
            this.holographicOwnerId = owner.getUniqueId();
            this.killerUUID = p.getKiller() == null ? null : p.getKiller().getUniqueId();
            this.xpStored = xpToStore;
            if (chestLocation.getWorld() != null)
                this.worldName = chestLocation.getWorld().getName();
        }
    }

    public ChestData(final List<ItemStack> inventory,
                     final Location chestLocation,
                     final String playerName,
                     final UUID playerUUID,
                     final Date chestDate,
                     final boolean isInfinity,
                     final boolean isRemovedBlock,
                     final Location holographicTimer,
                     final UUID asTimerId,
                     final UUID asStatusId,
                     final UUID asOwnerId,
                     final String worldName,
                     final int xpStored) {
        this(inventory, chestLocation, playerName, playerUUID, chestDate, isInfinity, isRemovedBlock, holographicTimer, asTimerId, asStatusId, asOwnerId, null, worldName, xpStored);
    }

    public ChestData(final List<ItemStack> inventory,
                     final Location chestLocation,
                     final String playerName,
                     final UUID playerUUID,
                     final Date chestDate,
                     final boolean isInfinity,
                     final boolean isRemovedBlock,
                     final Location holographicTimer,
                     final UUID asTimerId,
                     final UUID asStatusId,
                     final UUID asOwnerId,
                     final UUID killerUUID,
                     final String worldName,
                     final int xpStored) {
        this.inventory = inventory;
        this.chestLocation = chestLocation;
        this.playerName = playerName;
        this.playerUUID = playerUUID;
        this.chestDate = chestDate;
        this.isRemovedBlock = isRemovedBlock;
        this.isInfinity = isInfinity;
        this.holographicTimer = holographicTimer;
        this.holographicTimerId = asTimerId;
        this.holographicStatusId = asStatusId;
        this.holographicOwnerId = asOwnerId;
        this.killerUUID = killerUUID;
        this.worldName = worldName;
        this.xpStored = xpStored;
    }

    public UUID getHolographicTimerId() {
        return holographicTimerId;
    }

    public UUID getHolographicOwnerId() {
        return holographicOwnerId;
    }

    public UUID getHolographicStatusId() {
        return holographicStatusId;
    }

    public void setHolographicOwnerId(UUID holographicOwnerId) {
        this.holographicOwnerId = holographicOwnerId;
    }

    public void setHolographicStatusId(UUID holographicStatusId) {
        this.holographicStatusId = holographicStatusId;
    }

    public void setHolographicTimerId(UUID holographicTimerId) {
        this.holographicTimerId = holographicTimerId;
    }

    public List<ItemStack> getInventory() {
        return inventory;
    }

    public void cleanInventory() {
        inventory = new ArrayList<>();
    }

    public void setInventory(List<ItemStack> inventory) {
        this.inventory = inventory;
    }

    public void setRemovedBlock(final boolean removedBlock) {
        this.isRemovedBlock = removedBlock;
    }

    public Location getChestLocation() {
        return chestLocation;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getPlayerStringUUID() {
        return playerUUID + "";
    }

    public Date getChestDate() {
        return chestDate;
    }

    public boolean isRemovedBlock() {
        return isRemovedBlock;
    }

    public boolean isInfinity() {
        return isInfinity;
    }

    public Location getHolographicTimer() {
        return holographicTimer;
    }

    public String getWorldName() {
        return worldName;
    }

    public UUID getKillerUUID() {
        return killerUUID;
    }

    public void setKillerUUID(UUID killerUUID) {
        this.killerUUID = killerUUID;
    }

    public boolean removeArmorStand() {
        final int radius = 1;
        final int armorStandShiftY = 1;

        if (chestLocation.getWorld() != null) {
            final boolean foliaRuntime = DeadChestLoader.getSchedulerAdapter().isFoliaLikeRuntime();
            final boolean shouldForceLoadChunk = !foliaRuntime;

            if (foliaRuntime && !isChunkLoaded()) {
                return false;
            }

            if (shouldForceLoadChunk) {
                chestLocation.getChunk().setForceLoaded(true);
            }

            Collection<Entity> entities = chestLocation.getWorld().getNearbyEntities(
                    new Location(
                            chestLocation.getWorld(),
                            chestLocation.getX(),
                            chestLocation.getY() + armorStandShiftY,
                            chestLocation.getZ()
                    ), radius, radius, radius);

            boolean isEmpty = entities.size() > 0;
            for (Entity entity : entities) {
                if (entity.getUniqueId().equals(holographicOwnerId)
                        || entity.getUniqueId().equals(holographicTimerId)
                        || entity.getUniqueId().equals(holographicStatusId)) {
                    entity.remove();
                }
            }

            if (shouldForceLoadChunk && isChunkForceLoaded()) {
                chestLocation.getWorld().unloadChunk(chestLocation.getBlockX() >> 4, chestLocation.getBlockZ() >> 4);
                chestLocation.getChunk().setForceLoaded(false);
            }

            return isEmpty;
        }
        return false;
    }

    public boolean isChunkLoaded() {
        return chestLocation.getWorld() == null ||
                chestLocation.getWorld().isChunkLoaded(
                        chestLocation.getBlockX() >> 4,
                        chestLocation.getBlockZ() >> 4
                );
    }

    public boolean isChunkForceLoaded() {
        return chestLocation.getWorld() == null ||
                chestLocation.getWorld().isChunkForceLoaded(
                        chestLocation.getBlockX() >> 4,
                        chestLocation.getBlockZ() >> 4
                );
    }

    public int getXpStored() {
        return xpStored;
    }

    public void setXpStored(int xpStored) {
        this.xpStored = xpStored;
    }

    /**
     * @return stable identity of this chest, never {@code null}
     */
    public UUID getDeathId() {
        return deathId;
    }

    public void setDeathId(final UUID deathId) {
        this.deathId = deathId == null ? UUID.randomUUID() : deathId;
    }

    @Nonnull
    public ChestIntegrityState getIntegrityState() {
        return integrityState == null ? ChestIntegrityState.CONFIRMED : integrityState;
    }

    public void setIntegrityState(final ChestIntegrityState integrityState) {
        this.integrityState = integrityState == null ? ChestIntegrityState.CONFIRMED : integrityState;
    }

    public long getIntegritySequence() {
        return integritySequence;
    }

    public void setIntegritySequence(final long integritySequence) {
        this.integritySequence = integritySequence;
    }

    public UUID getIntegrityOwner() {
        return integrityOwner;
    }

    public void setIntegrityOwner(final UUID integrityOwner) {
        this.integrityOwner = integrityOwner;
    }

    /**
     * Indicates whether the chest is reconciled with the player data and can
     * therefore be opened, expired or emptied.
     *
     * @return {@code true} when the chest is settled
     */
    public boolean isSettled() {
        return getIntegrityState().isSettled();
    }

    /**
     * Atomically takes ownership of the content hand over.
     *
     * @return {@code true} for the single caller allowed to move the items
     */
    public boolean beginTransfer() {
        return transferLock.compareAndSet(false, true);
    }

    /**
     * Releases a hand over taken by {@link #beginTransfer()} when it could not
     * be completed, so the chest stays usable.
     */
    public void abortTransfer() {
        transferLock.set(false);
    }

    public void save(@Nonnull final Consumer<Boolean> containsChestOnLoc) {
        ChestDataRepository.saveAsync(this, containsChestOnLoc);
    }

    public void update(@Nonnull final Consumer<Boolean> containsChestOnLoc) {
        ChestDataRepository.updateAsync(this, containsChestOnLoc);
    }

    public void remove() {
        ChestDataRepository.removeAsync(this);
    }


    enum Indexes {WORLD_NAME, LOC_X, LOC_Y, LOC_Z}
}
