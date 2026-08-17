package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.drops.LockedDropService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Since Minecraft 1.17, Paper stores entities apart from their chunk and loads
 * them through a dedicated event. Reserved drops are therefore found back here
 * rather than in {@link org.bukkit.event.world.ChunkLoadEvent}.
 * <p>
 * This listener is only registered when the platform provides the event, see
 * {@link LockedDropService#supportsEntitiesLoadEvent()}.
 */
public class LockedDropEntitiesListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        LockedDropService.trackLoadedDrops(event.getEntities());
    }
}
