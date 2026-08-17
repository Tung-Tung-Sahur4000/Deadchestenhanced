package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.drops.DespawnRateAdvisor;
import me.crylonz.deadchest.integrity.ChestIntegrityService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;

/**
 * Keeps the deadchest storage in sync with the player files.
 * <p>
 * Three moments matter:
 * <ul>
 *     <li>respawn, the first instant where the emptied inventory can be written
 *     to disk together with the stamp that proves the death happened;</li>
 *     <li>quit, same thing for a player who never respawned or who looted a
 *     chest just before leaving;</li>
 *     <li>join, where the player file comes back from the disk and can be
 *     compared with what the plugin believes, which is how a crash that rolled
 *     the player back is detected.</li>
 * </ul>
 */
public class PlayerIntegrityListener implements Listener {

    /**
     * Persists the post-death state and confirms the deadchest created by the
     * death.
     * <p>
     * Runs one tick later on purpose: during the event the server has not
     * finished moving the player to its respawn state yet, and saving too early
     * would write a state that still contains the death inventory.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        DeadChestLoader.getSchedulerAdapter().runForEntity(player, () -> ChestIntegrityService.flushAndSettle(player));
    }

    /**
     * Settles what the player carries away when leaving.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        ChestIntegrityService.flushAndSettle(event.getPlayer());
    }

    /**
     * Compares the player file that just came back from the disk with the chests
     * waiting on it, and settles or cancels them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        ChestIntegrityService.reconcile(event.getPlayer());
        warnOperatorAboutDespawnRate(event.getPlayer());
    }

    /**
     * Tells an operator joining the server that spigot.yml would take the reserved
     * drops away before their configured lifetime. Console says it once at startup,
     * which scrolls away, so the people who can act on it are told in game too.
     *
     * @param player player who just joined
     */
    private void warnOperatorAboutDespawnRate(Player player) {
        if (player == null || !player.isOp()) {
            return;
        }

        final List<String> mismatches = DespawnRateAdvisor.findMismatches();
        if (mismatches.isEmpty()) {
            return;
        }

        player.sendMessage(ChatColor.RED + "[DeadChest] " + ChatColor.YELLOW
                + "The server despawn timer is shorter than the reserved drop lifetime:");
        for (String mismatch : mismatches) {
            player.sendMessage(ChatColor.YELLOW + " - " + mismatch);
        }
    }
}
