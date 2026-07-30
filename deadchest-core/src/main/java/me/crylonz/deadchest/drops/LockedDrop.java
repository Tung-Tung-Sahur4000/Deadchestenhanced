package me.crylonz.deadchest.drops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Tracking data of a single vanilla drop reserved for the player who died.
 * <p>
 * The expiration is stored as a wall clock timestamp so the countdown keeps
 * running while the chunk is unloaded or while nobody is around, exactly like a
 * DeadChest timer would.
 */
public class LockedDrop {

    private final UUID itemId;
    private final UUID ownerId;
    private final UUID worldId;
    private final long creationTime;
    private final long expirationTime;

    private double x;
    private double y;
    private double z;
    private long integritySequence;
    private boolean confirmed = true;

    public LockedDrop(UUID itemId, UUID ownerId, Location location, long creationTime, long expirationTime) {
        this.itemId = itemId;
        this.ownerId = ownerId;
        this.worldId = location != null && location.getWorld() != null ? location.getWorld().getUID() : null;
        this.creationTime = creationTime;
        this.expirationTime = expirationTime;
        if (location != null) {
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
        }
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    /**
     * @return epoch milliseconds of the death that created this drop
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * @return sequence stamped on the owner player data for this death, 0 when the
     * drop carries no crash protection stamp
     */
    public long getIntegritySequence() {
        return integritySequence;
    }

    /**
     * @return {@code false} while the death that created this drop is still
     * waiting for the player data to be written to disk
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    public void setIntegrity(long integritySequence, boolean confirmed) {
        this.integritySequence = integritySequence;
        this.confirmed = confirmed;
    }

    /**
     * @return epoch milliseconds when the drop must be removed, 0 when it never expires
     */
    public long getExpirationTime() {
        return expirationTime;
    }

    public World getWorld() {
        return worldId == null ? null : Bukkit.getWorld(worldId);
    }

    /**
     * @return last known position of the item entity, {@code null} when its world is gone
     */
    public Location getLocation() {
        World world = getWorld();
        return world == null ? null : new Location(world, x, y, z);
    }

    /**
     * Items float, fall and get pushed by water, so the tracked position follows
     * the entity to keep chunk lookups accurate.
     *
     * @param location current position of the item entity
     */
    public void updateLocation(Location location) {
        if (location == null) {
            return;
        }
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
    }

    public boolean isExpired(long now) {
        return expirationTime > 0L && expirationTime <= now;
    }
}
