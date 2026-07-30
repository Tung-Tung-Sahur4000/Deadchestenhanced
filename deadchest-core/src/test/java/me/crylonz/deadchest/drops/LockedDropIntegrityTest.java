package me.crylonz.deadchest.drops;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.integrity.ChestIntegrityService;
import me.crylonz.deadchest.integrity.PlayerDataStamp;
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
import java.util.List;
import java.util.logging.Logger;

import static me.crylonz.deadchest.utils.ConfigKey.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the crash duplication protection of the vanilla drop mode.
 * <p>
 * A server killed without a clean shutdown rolls the player file back to before
 * the death while the reserved drops stay in the chunk : the player then comes
 * back with the items in the inventory AND finds them on the ground. The drops
 * are therefore stamped exactly like a deadchest is, and judged at the next login.
 */
class LockedDropIntegrityTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private DeadChestConfig config;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new WorldMock(Material.GRASS_BLOCK, 64);
        server.addWorld(world);
        world.loadChunk(0, 0);

        DeadChestLoader.plugin = MockBukkit.createMockPlugin();
        DeadChestLoader.log = Logger.getLogger("LockedDropIntegrityTest");
        DeadChestLoader.local = new Localization();
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());

        config = mock(DeadChestConfig.class);
        when(config.getBoolean(VANILLA_DROP_ENABLED)).thenReturn(true);
        when(config.getBoolean(VANILLA_DROP_OWNER_ONLY_PICKUP)).thenReturn(true);
        when(config.getBoolean(VANILLA_DROP_PROTECT_FROM_DESPAWN)).thenReturn(true);
        when(config.getBoolean(DISPLAY_POSITION_ON_DEATH)).thenReturn(false);
        when(config.getInt(VANILLA_DROP_DESPAWN_SECONDS)).thenReturn(300);
        when(config.getIgnoredEntries()).thenReturn(new ArrayList<>());
        when(config.getBoolean(INTEGRITY_PROTECTION_ENABLED)).thenReturn(true);
        when(config.getBoolean(INTEGRITY_FLUSH_PLAYER_DATA)).thenReturn(true);
        when(config.getString(INTEGRITY_ON_ROLLBACK)).thenReturn("void");
        DeadChestLoader.config = config;

        LockedDropService.clearTracking();
        ChestIntegrityService.resetSessionState();

        player = server.addPlayer("Steve");
        player.teleport(new Location(world, 0, 65, 0));
    }

    @AfterEach
    void tearDown() {
        LockedDropService.clearTracking();
        ChestIntegrityService.resetSessionState();
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());
        MockBukkit.unmock();
    }

    private void die() {
        List<ItemStack> drops = new ArrayList<>(Collections.singletonList(new ItemStack(Material.DIAMOND, 3)));
        LockedDropService.handlePlayerDeath(new PlayerDeathEvent(player, drops, 0, ""), player);
    }

    private Item onlyDrop() {
        for (Item item : world.getEntitiesByClass(Item.class)) {
            return item;
        }
        return null;
    }

    @Test
    void theDeathIsStampedOnThePlayerAndTheDropsAreStaged() {
        die();

        assertTrue(PlayerDataStamp.readSequence(player) > 0L, "The death is stamped inside the player data");

        Item drop = onlyDrop();
        assertTrue(LockedDropService.getIntegritySequence(drop) > 0L, "The drop carries the same sequence");
        assertFalse(LockedDropService.isIntegrityConfirmed(drop), "It waits for the player data to be written");
    }

    @Test
    void respawnConfirmsTheDrops() {
        die();

        ChestIntegrityService.flushAndSettle(player);

        assertTrue(LockedDropService.isIntegrityConfirmed(onlyDrop()), "The post death state reached the disk");
        assertEquals(1, LockedDropService.getTrackedDropAmount(), "And the drop stays where it is");
    }

    @Test
    void aLoginProvingTheDeathWasSavedKeepsTheDrops() {
        die();

        // The player file carries the stamp, so the death really happened
        ChestIntegrityService.reconcile(player);

        assertEquals(1, LockedDropService.getTrackedDropAmount());
        assertTrue(onlyDrop().isValid());
        assertTrue(LockedDropService.isIntegrityConfirmed(onlyDrop()));
    }

    @Test
    void aRolledBackDeathRemovesTheDuplicatedDrops() {
        die();
        Item drop = onlyDrop();

        // The crash rolled the player file back to before the death: the stamp is
        // gone and the items are back in the inventory.
        PlayerDataStamp.writeSequence(player, 0L);
        ChestIntegrityService.reconcile(player);

        assertFalse(drop.isValid(), "The drop on the ground is a duplicate and is removed");
        assertEquals(0, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void keepModeLeavesTheDuplicateOnTheGround() {
        when(config.getString(INTEGRITY_ON_ROLLBACK)).thenReturn("keep");
        die();
        Item drop = onlyDrop();

        PlayerDataStamp.writeSequence(player, 0L);
        ChestIntegrityService.reconcile(player);

        assertTrue(drop.isValid(), "'keep' asks for the duplicate to stay");
        assertEquals(1, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void protectionDisabledKeepsTheHistoricBehavior() {
        when(config.getBoolean(INTEGRITY_PROTECTION_ENABLED)).thenReturn(false);
        die();
        Item drop = onlyDrop();

        assertEquals(0L, LockedDropService.getIntegritySequence(drop), "No stamp is written");
        assertTrue(LockedDropService.isIntegrityConfirmed(drop));

        PlayerDataStamp.writeSequence(player, 0L);
        ChestIntegrityService.reconcile(player);

        assertTrue(drop.isValid(), "An unstamped drop is never judged as a duplicate");
    }

    @Test
    void aDropComingBackFromAnUnloadedChunkIsJudgedToo() {
        die();
        Item drop = onlyDrop();
        long sequence = LockedDropService.getIntegritySequence(drop);
        assertTrue(sequence > 0L);

        // The verdict of the login is known, but this drop was asleep at that moment
        LockedDropService.clearTracking();
        LockedDropService.reconcileIntegrity(player, 0L);

        LockedDropService.trackExistingDrop(drop);

        assertFalse(drop.isValid(), "It is removed as soon as its chunk brings it back");
        assertEquals(0, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void everyDropOfTheSameDeathSharesTheStamp() {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(Material.DIAMOND, 1));
        drops.add(new ItemStack(Material.IRON_INGOT, 1));
        drops.add(new ItemStack(Material.GOLD_INGOT, 1));
        LockedDropService.handlePlayerDeath(new PlayerDeathEvent(player, drops, 0, ""), player);

        long sequence = PlayerDataStamp.readSequence(player);
        assertEquals(3, LockedDropService.getTrackedDropAmount());
        for (Item item : world.getEntitiesByClass(Item.class)) {
            assertEquals(sequence, LockedDropService.getIntegritySequence(item));
        }
    }

    @Test
    void twoDeathsGetTwoDifferentSequences() {
        die();
        long first = PlayerDataStamp.readSequence(player);
        ChestIntegrityService.flushAndSettle(player);

        die();
        long second = PlayerDataStamp.readSequence(player);

        assertTrue(second > first, "The sequence stays monotonic across deaths");
        assertEquals(second, LockedDropService.highestStampedSequence(player.getUniqueId()));
    }
}
