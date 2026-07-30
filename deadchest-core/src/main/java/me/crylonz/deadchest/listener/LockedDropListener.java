package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.drops.LockedDropService;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.UUID;

/**
 * Enforces the vanilla drop mode rules : drops reserved to the player who died
 * cannot be taken by anybody else and never despawn before their configured
 * lifetime.
 */
public class LockedDropListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        final Item item = event.getItem();
        if (!LockedDropService.isLocked(item)) {
            return;
        }

        if (event.getEntity() instanceof Player
                && LockedDropService.canPickUp((Player) event.getEntity(), item)) {
            return;
        }

        // Mobs never loot a reserved drop, and other players only with a bypass permission.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (LockedDropService.isLocked(event.getItem()) && LockedDropService.isOwnerOnlyPickup()) {
            // Hoppers and minecarts must not empty a reserved drop either.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        final Item item = event.getEntity();
        if (LockedDropService.isLocked(item) && !LockedDropService.isExpired(item, System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        final UUID sourceOwner = LockedDropService.getOwner(event.getEntity());
        final UUID targetOwner = LockedDropService.getOwner(event.getTarget());

        if (sourceOwner == null && targetOwner == null) {
            return;
        }

        // Merging drops of two different owners would transfer the lock, so keep them apart.
        if (sourceOwner == null || !sourceOwner.equals(targetOwner)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        LockedDropService.trackLoadedDrops(event.getChunk());
    }
}
