package me.crylonz.deadchest.compass;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.drops.LockedDropService;
import me.crylonz.deadchest.drops.LockedDropSite;
import me.crylonz.deadchest.listener.GraveCompassListener;
import me.crylonz.deadchest.utils.ConfigKey;
import me.crylonz.deadchest.utils.DeadChestConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static me.crylonz.deadchest.utils.ConfigKey.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the compass when the vanilla drop mode replaces the chests : there is no
 * DeadChest to point at, so the destination is the place where the reserved drops
 * are waiting.
 */
class GraveCompassVanillaDropTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private DeadChestConfig config;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        world.loadChunk(0, 0);

        DeadChestLoader.plugin = MockBukkit.createMockPlugin();
        DeadChestLoader.log = Logger.getLogger("GraveCompassVanillaDropTest");

        final Localization localization = new Localization();
        final Map<String, Object> messages = new HashMap<>();
        messages.put("common.prefix", "[DeadChest] ");
        messages.put("compass.name", "Deadchest Compass");
        messages.put("compass.lore", "Points to X: {0} Y: {1} Z: {2} in {3}");
        messages.put("compass.received", "A compass now points to your Deadchest");
        messages.put("death.drop-position", "Your items dropped at X: {0} Y: {1} Z: {2}");
        messages.put("death.drop-locked", "Only you can pick them up for {0}m {1}s");
        localization.set(messages);
        DeadChestLoader.local = localization;
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());

        config = mock(DeadChestConfig.class);
        when(config.getBoolean(RESPAWN_COMPASS)).thenReturn(true);
        when(config.getInt(RESPAWN_COMPASS_UPDATE_SECONDS)).thenReturn(5);
        when(config.getBoolean(VANILLA_DROP_ENABLED)).thenReturn(true);
        when(config.getBoolean(VANILLA_DROP_OWNER_ONLY_PICKUP)).thenReturn(true);
        when(config.getBoolean(VANILLA_DROP_PROTECT_FROM_DESPAWN)).thenReturn(true);
        when(config.getBoolean(ConfigKey.DISPLAY_POSITION_ON_DEATH)).thenReturn(true);
        when(config.getInt(VANILLA_DROP_DESPAWN_SECONDS)).thenReturn(300);
        when(config.getIgnoredEntries()).thenReturn(new ArrayList<>());
        DeadChestLoader.config = config;

        LockedDropService.clearTracking();
        player = server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        LockedDropService.clearTracking();
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());
        MockBukkit.unmock();
    }

    private Item lockDropAt(Location location, long creationTime) {
        // The maintenance pass only touches drops whose chunk is loaded, like on a real server
        world.loadChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        Item item = world.dropItemNaturally(location, new ItemStack(Material.DIAMOND, 1));
        LockedDropService.lockDrop(item, player.getUniqueId(), player.getName(), creationTime, 0L);
        return item;
    }

    @Test
    void theDropSiteIsAValidCompassDestination() {
        lockDropAt(new Location(world, 12, 64, -30), 1_000L);

        GraveCompassTarget target = GraveCompassService.latestTarget(player);

        assertNotNull(target, "The reserved drops are a destination even without any chest");
        assertEquals(12, target.location().getBlockX());
        assertEquals(-30, target.location().getBlockZ());
        assertEquals("world", target.worldName());
    }

    @Test
    void noDropsMeansNoDestination() {
        assertNull(GraveCompassService.latestTarget(player));
        assertNull(LockedDropService.latestDropSite(player));
    }

    @Test
    void theNewestDeathWins() {
        lockDropAt(new Location(world, 5, 64, 5), 1_000L);
        lockDropAt(new Location(world, 80, 70, 80), 9_000L);

        GraveCompassTarget target = GraveCompassService.latestTarget(player);

        assertNotNull(target);
        assertEquals(80, target.location().getBlockX());
    }

    @Test
    void dropsOfAnotherPlayerAreNotADestination() {
        PlayerMock otherPlayer = server.addPlayer("Alex");
        Item item = world.dropItemNaturally(new Location(world, 3, 64, 3), new ItemStack(Material.DIAMOND, 1));
        LockedDropService.lockDrop(item, otherPlayer.getUniqueId(), otherPlayer.getName(), 1_000L, 0L);

        assertNull(LockedDropService.latestDropSite(player));
        assertNotNull(LockedDropService.latestDropSite(otherPlayer));
    }

    @Test
    void respawningInVanillaDropModeGivesACompassPointingAtTheDrops() {
        lockDropAt(new Location(world, 40, 64, -12), System.currentTimeMillis());

        GraveCompassService.giveOnRespawn(player);

        ItemStack compass = firstCompass(player);
        assertNotNull(compass, "A compass is handed out even though there is no chest");
        assertTrue(compass.getItemMeta().getLore().get(0).contains("X: 40"), "It points at the drops");
    }

    @Test
    void theCompassIsRemovedOnceTheDropsAreGone() {
        Item item = lockDropAt(new Location(world, 40, 64, -12), System.currentTimeMillis());
        GraveCompassService.giveOnRespawn(player);
        assertNotNull(firstCompass(player));

        // Picked up or expired : nothing left to walk back to
        item.remove();
        LockedDropService.tick();
        GraveCompassService.refresh(player);

        assertNull(firstCompass(player), "The compass disappears like it does when a chest is collected");
    }

    @Test
    void theCompassIsNeverLockedAsADrop() {
        lockDropAt(new Location(world, 40, 64, -12), System.currentTimeMillis());
        GraveCompassService.giveOnRespawn(player);
        ItemStack compass = firstCompass(player);
        assertNotNull(compass);

        // The compass listener strips it at LOWEST, before the vanilla drop mode
        // takes the drops over at LOW.
        List<ItemStack> drops = new ArrayList<>(Collections.singletonList(compass));
        PlayerDeathEvent event = new PlayerDeathEvent(player, drops, 0, "");
        new GraveCompassListener().onPlayerDeath(event);
        LockedDropService.clearTracking();
        LockedDropService.handlePlayerDeath(event, player);

        assertTrue(event.getDrops().isEmpty(), "The compass is not left in the drops");
        assertEquals(0, LockedDropService.getTrackedDropAmount(), "And it is never reserved as a locked drop");
    }

    @Test
    void aDropSiteKeepsItsIdentityBetweenUpdates() {
        lockDropAt(new Location(world, 7, 64, 7), 4_242L);

        LockedDropSite first = LockedDropService.latestDropSite(player);
        LockedDropSite second = LockedDropService.latestDropSite(player);

        assertNotNull(first);
        assertEquals(first.getId(), second.getId(), "A stable id keeps the compass from being rewritten every tick");
    }

    private ItemStack firstCompass(PlayerMock target) {
        for (ItemStack item : target.getInventory().getContents()) {
            if (GraveCompassService.isGraveCompass(item)) {
                return item;
            }
        }
        return null;
    }
}
