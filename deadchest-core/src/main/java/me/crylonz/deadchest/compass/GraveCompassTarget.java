package me.crylonz.deadchest.compass;

import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.drops.LockedDropSite;
import org.bukkit.Location;

import javax.annotation.Nullable;

/**
 * Where the compass has to point.
 * <p>
 * A destination is either a DeadChest or, when the vanilla drop mode replaces the
 * chests, the place where the reserved drops are waiting. Both are handled the
 * same way by the compass, only the identifier differs so an unchanged
 * destination never rewrites the item.
 */
public final class GraveCompassTarget {

    private final String id;
    private final Location location;
    private final String worldName;
    private final long timeMillis;

    private GraveCompassTarget(String id, Location location, String worldName, long timeMillis) {
        this.id = id;
        this.location = location;
        this.worldName = worldName;
        this.timeMillis = timeMillis;
    }

    /**
     * @param chest chest to walk back to
     * @return matching destination, or {@code null} when the chest has no usable position
     */
    @Nullable
    public static GraveCompassTarget ofChest(final ChestData chest) {
        if (chest == null || chest.getChestLocation() == null || chest.getChestDate() == null) {
            return null;
        }

        final Location location = chest.getChestLocation();
        return new GraveCompassTarget(
                chest.getDeathId() == null ? null : chest.getDeathId().toString(),
                location,
                location.getWorld() == null ? chest.getWorldName() : location.getWorld().getName(),
                chest.getChestDate().getTime());
    }

    /**
     * @param site place where the reserved vanilla drops are waiting
     * @return matching destination, or {@code null} when the site has no usable position
     */
    @Nullable
    public static GraveCompassTarget ofDropSite(final LockedDropSite site) {
        if (site == null || site.getLocation() == null) {
            return null;
        }

        final Location location = site.getLocation();
        return new GraveCompassTarget(
                site.getId(),
                location,
                location.getWorld() == null ? "" : location.getWorld().getName(),
                site.getCreationTime());
    }

    /**
     * @return identifier of the destination, {@code null} when it cannot be identified
     */
    @Nullable
    public String id() {
        return id;
    }

    public Location location() {
        return location;
    }

    public String worldName() {
        return worldName;
    }

    /**
     * @return epoch milliseconds of the death, used to keep the newest destination
     */
    public long timeMillis() {
        return timeMillis;
    }
}
