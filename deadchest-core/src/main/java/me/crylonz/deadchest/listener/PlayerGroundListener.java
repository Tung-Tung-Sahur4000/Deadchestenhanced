package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.placement.GroundTracker;
import me.crylonz.deadchest.utils.ConfigKey;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import static me.crylonz.deadchest.DeadChestLoader.config;

/**
 * Feeds {@link GroundTracker} with the last block a player safely stood on.
 * <p>
 * Only the placement rules that have no usable death position need it, so the
 * sampling stops entirely when they are all disabled.
 */
public class PlayerGroundListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!trackingNeeded()) {
            return;
        }

        final Location from = event.getFrom();
        final Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return; // head movement, nothing to sample
        }

        GroundTracker.track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (trackingNeeded()) {
            GroundTracker.track(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        GroundTracker.forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        GroundTracker.forget(event.getPlayer());
    }

    private boolean sameBlock(final Location from, final Location to) {
        return from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private boolean trackingNeeded() {
        return config != null && (config.getBoolean(ConfigKey.PLACEMENT_LAVA_SMART)
                || config.getBoolean(ConfigKey.PLACEMENT_SUFFOCATION)
                || config.getBoolean(ConfigKey.PLACEMENT_POWDER_SNOW)
                || config.getBoolean(ConfigKey.PLACEMENT_VOID));
    }
}
