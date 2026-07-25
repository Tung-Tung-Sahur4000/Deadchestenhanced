package me.crylonz.deadchest.compass;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.listener.GraveCompassListener;
import me.crylonz.deadchest.utils.ConfigKey;
import me.crylonz.deadchest.utils.DeadChestConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the deadchest compass: who gets one, where it points, and the fact that
 * it never becomes an item like the others.
 */
class GraveCompassServiceTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private DeadChestConfig config;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");

        DeadChestLoader.plugin = MockBukkit.createMockPlugin();
        DeadChestLoader.log = Logger.getLogger("GraveCompassServiceTest");
        final Localization localization = new Localization();
        final java.util.Map<String, Object> messages = new java.util.HashMap<>();
        messages.put("common.prefix", "[DeadChest] ");
        messages.put("compass.name", "Deadchest Compass");
        messages.put("compass.lore", "Points to X: {0} Y: {1} Z: {2} in {3}");
        messages.put("compass.received", "A compass now points to your Deadchest");
        localization.set(messages);
        DeadChestLoader.local = localization;
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());

        config = mock(DeadChestConfig.class);
        when(config.getBoolean(ConfigKey.RESPAWN_COMPASS)).thenReturn(true);
        when(config.getInt(ConfigKey.RESPAWN_COMPASS_UPDATE_SECONDS)).thenReturn(5);
        DeadChestLoader.config = config;

        player = server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());
        MockBukkit.unmock();
    }

    @Test
    void respawningWithAChestGivesACompass() {
        trackChest(10, new Date());

        GraveCompassService.giveOnRespawn(player);

        assertEquals(1, countCompasses(), "The player must respawn with exactly one compass");
    }

    @Test
    void respawningWithoutAChestGivesNothing() {
        GraveCompassService.giveOnRespawn(player);

        assertEquals(0, countCompasses());
    }

    @Test
    void respawningTwiceDoesNotStackCompasses() {
        trackChest(11, new Date());

        GraveCompassService.giveOnRespawn(player);
        GraveCompassService.giveOnRespawn(player);

        assertEquals(1, countCompasses());
    }

    @Test
    void theCompassFollowsTheLatestChest() {
        trackChest(12, new Date(1_000L));
        final ChestData latest = trackChest(13, new Date(9_000L));

        GraveCompassService.giveOnRespawn(player);

        final ItemStack compass = firstCompass();
        assertNotNull(compass);
        assertTrue(compass.getItemMeta().getLore().get(0).contains(String.valueOf(latest.getChestLocation().getBlockX())),
                "The compass describes the most recent chest");
    }

    @Test
    void collectingTheLastChestRemovesTheCompass() {
        final ChestData chest = trackChest(14, new Date());
        GraveCompassService.giveOnRespawn(player);
        assertEquals(1, countCompasses());

        // Detached rather than removed: the world side of a pickup is not what the
        // compass reacts to.
        DeadChestLoader.getChestDataCache().detachChestData(chest);
        GraveCompassService.refresh(player);

        assertEquals(0, countCompasses(), "Nothing left to walk back to, the compass goes away");
    }

    @Test
    void theCompassIsNeverStoredInAGraveNorDropped() {
        trackChest(15, new Date());
        GraveCompassService.giveOnRespawn(player);

        final List<ItemStack> drops = new ArrayList<>();
        drops.add(GraveCompassService.createCompass(trackChest(16, new Date())));
        drops.add(new ItemStack(Material.DIAMOND));

        new GraveCompassListener().onPlayerDeath(new PlayerDeathEvent(player, drops, 0, ""));

        assertEquals(0, countCompasses(), "The compass leaves the inventory before the grave is filled");
        assertEquals(1, drops.size(), "The compass is not part of the death drops");
        assertEquals(Material.DIAMOND, drops.get(0).getType());
    }

    @Test
    void theCompassIsRewrittenOnlyWhenItsTargetChanges() {
        trackChest(18, new Date(1_000L));
        GraveCompassService.giveOnRespawn(player);
        final ItemStack firstState = firstCompass();

        GraveCompassService.refresh(player);
        assertSame(firstState, firstCompass(), "An unchanged target must not replace the item");

        trackChest(19, new Date(9_000L));
        GraveCompassService.refresh(player);
        assertNotSame(firstState, firstCompass(), "A newer chest retargets the compass");
    }

    @Test
    void theCompassIsAlwaysTaggedWhenItIsHandedOut() {
        // A compass that cannot be tagged is not given at all: an untagged one
        // would be dropped on death and stored in graves like a normal item.
        final ChestData chest = trackChest(20, new Date());

        final ItemStack compass = GraveCompassService.createCompass(chest);

        assertNotNull(compass);
        assertTrue(GraveCompassService.isGraveCompass(compass));
    }

    @Test
    void aPlainCompassIsNotAGraveCompass() {
        assertFalse(GraveCompassService.isGraveCompass(new ItemStack(Material.COMPASS)));
        assertFalse(GraveCompassService.isGraveCompass(new ItemStack(Material.DIAMOND)));
        assertFalse(GraveCompassService.isGraveCompass(null));
    }

    @Test
    void anUnreconciledChestIsNotATarget() {
        final ChestData chest = trackChest(17, new Date());
        chest.setIntegrityState(me.crylonz.deadchest.integrity.ChestIntegrityState.PENDING);

        assertNull(GraveCompassService.latestChest(player), "A chest that may be cancelled is not a destination");
    }

    // ------------------------------------------------------------------

    private int countCompasses() {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (GraveCompassService.isGraveCompass(item)) {
                count++;
            }
        }
        return count;
    }

    private ItemStack firstCompass() {
        for (ItemStack item : player.getInventory().getContents()) {
            if (GraveCompassService.isGraveCompass(item)) {
                return item;
            }
        }
        return null;
    }

    private ChestData trackChest(int x, Date date) {
        final Location location = new Location(world, x, 64, x);
        final List<ItemStack> inventory = new ArrayList<>();
        inventory.add(new ItemStack(Material.DIAMOND, 1));

        final ChestData chestData = new ChestData(
                inventory,
                location,
                player.getName(),
                player.getUniqueId(),
                date,
                false,
                false,
                location.clone().add(0, 1, 0),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                world.getName(),
                0
        );

        DeadChestLoader.getChestDataCache().addChestData(chestData);
        return chestData;
    }
}
