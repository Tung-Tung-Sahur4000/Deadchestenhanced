package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.compass.GraveCompassService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;

/**
 * Owns the life cycle of the deadchest compass.
 * <p>
 * The compass is given back on every respawn while a chest is waiting, and it is
 * kept out of everything else: it is never stored in a deadchest, never dropped
 * on death, never dropped by hand and never moved into a container.
 */
public class GraveCompassListener implements Listener {

    /**
     * Strips the compass before anything else looks at the inventory.
     * <p>
     * Runs at the lowest priority on purpose: the deadchest generation reads the
     * inventory right after, and a compass stored inside a grave would come back
     * duplicated at the next respawn.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        final Player player = event.getEntity();
        GraveCompassService.removeAll(player);
        GraveCompassService.removeFromDrops(event.getDrops());
    }

    /**
     * Gives the compass to the respawned player, one tick later so the respawn
     * inventory is the one being filled.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        DeadChestLoader.getSchedulerAdapter().runForEntity(player, () -> GraveCompassService.giveOnRespawn(player));
    }

    /**
     * The compass belongs to the player who died, it cannot be thrown away.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (GraveCompassService.isGraveCompass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks any move of the compass into a container.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        final Inventory topInventory = event.getView().getTopInventory();
        if (!isExternalContainer(topInventory)) {
            return;
        }

        if (!GraveCompassService.isGraveCompass(event.getCurrentItem())
                && !GraveCompassService.isGraveCompass(event.getCursor())) {
            return;
        }

        final boolean intoContainer = event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getRawSlot() < topInventory.getSize();
        if (intoContainer) {
            event.setCancelled(true);
        }
    }

    /**
     * Same guard for a drag across several slots.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        final Inventory topInventory = event.getView().getTopInventory();
        if (!isExternalContainer(topInventory) || !GraveCompassService.isGraveCompass(event.getOldCursor())) {
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topInventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * @return {@code true} when the open window is a real container, not the
     * player's own inventory
     */
    private boolean isExternalContainer(final Inventory topInventory) {
        return topInventory != null
                && topInventory.getType() != InventoryType.CRAFTING
                && topInventory.getType() != InventoryType.PLAYER;
    }
}
