package me.crylonz.deadchest.drops;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Where a player died in vanilla drop mode, and when.
 * <p>
 * A site groups every drop reserved by the same death, so the plugin can point a
 * compass at it and tell the player where to walk back to, exactly like it does
 * with a DeadChest.
 */
public class LockedDropSite {

    private final UUID ownerId;
    private final Location location;
    private final long creationTime;

    public LockedDropSite(UUID ownerId, Location location, long creationTime) {
        this.ownerId = ownerId;
        this.location = location;
        this.creationTime = creationTime;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Location getLocation() {
        return location;
    }

    /**
     * @return epoch milliseconds of the death that created the drops
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * @return stable identifier of the site, so a compass already pointing at it
     * is not rewritten on every update
     */
    public String getId() {
        return "drop:" + ownerId + ":" + creationTime;
    }
}
