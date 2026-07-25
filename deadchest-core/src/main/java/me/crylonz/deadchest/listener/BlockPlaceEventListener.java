package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.placement.BuildAccessProbe;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import static me.crylonz.deadchest.DeadChestLoader.local;

public class BlockPlaceEventListener implements Listener {

    @EventHandler
    public void onBlockPlaceEvent(org.bukkit.event.block.BlockPlaceEvent e) {
        // The grave placement asks the server where a player may build by firing
        // a probe event. Answering it would deny every position next to a grave,
        // which is exactly where a second grave has to go.
        if (BuildAccessProbe.isProbing()) {
            return;
        }

        // Disable double chest for grave chest
        if (e.getBlock().getType() == Material.CHEST) {
            for (BlockFace face : BlockFace.values()) {
                Block block = e.getBlock().getRelative(face);
                if (block.getType() == Material.CHEST) {
                    final ChestData chestData = DeadChestLoader.getChestData(block.getLocation());
                    if(chestData != null){
                        e.setCancelled(true);
                        e.getPlayer().sendMessage(local.prefixed("chest.double-block"));
                    }
                }
            }
        }
    }
}
