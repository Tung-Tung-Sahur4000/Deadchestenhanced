package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.db.InMemoryChestStore;
import me.crylonz.deadchest.utils.ConfigKey;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static me.crylonz.deadchest.DeadChestLoader.config;
import static me.crylonz.deadchest.utils.Utils.generateLog;
import static me.crylonz.deadchest.utils.Utils.isGraveBlock;

public class ExplosionListener implements Listener {

    @EventHandler
    public void onEntityExplodeEvent(EntityExplodeEvent e) {
        chestExplosionHandler(e);
    }

    @EventHandler
    public void onBlockExplodeEvent(BlockExplodeEvent e) {
        chestExplosionHandler(e);
    }

    public void chestExplosionHandler(Event e) {
        List<Block> blocklist = new ArrayList<>();
        if (e instanceof EntityExplodeEvent) {
            blocklist = ((EntityExplodeEvent) e).blockList();
        } else if (e instanceof BlockExplodeEvent) {
            blocklist = ((BlockExplodeEvent) e).blockList();
        }

        if (!blocklist.isEmpty()) {
            final InMemoryChestStore inMemoryChestStore = DeadChestLoader.getChestDataCache();
            // Walked backwards: removing a protected chest from the blast list
            // while going forward skipped the next block, so a second deadchest
            // right next to the first one was blown up anyway.
            for (int i = blocklist.size() - 1; i >= 0; i--) {
                Block block = blocklist.get(i);
                if (!isGraveBlock(block.getType())) {
                    continue;
                }
                final ChestData chestData = inMemoryChestStore.getChestData(block.getLocation());

                if (chestData != null) {
                    if (config.getBoolean(ConfigKey.INDESTRUCTIBLE_CHEST)) {
                        blocklist.remove(i);
                        generateLog("Deadchest of [" + chestData.getPlayerName() + "] was protected from explosion in " + Objects.requireNonNull(chestData.getChestLocation().getWorld()).getName());
                    } else {
                        inMemoryChestStore.removeChestData(chestData);
                        generateLog("Deadchest of [" + chestData.getPlayerName() + "] was blown up in " + Objects.requireNonNull(chestData.getChestLocation().getWorld()).getName());
                    }
                }
            }
        }
    }
}
