package me.crylonz.deadchest.integrity;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.TestDatabase;
import me.crylonz.deadchest.db.ChestDataRepository;
import me.crylonz.deadchest.utils.ConfigKey;
import me.crylonz.deadchest.utils.DeadChestConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Covers the crash consistency handshake between the deadchest storage and the
 * vanilla player files.
 * <p>
 * A crash is simulated the way it actually happens: the plugin side of a death
 * is written, the player side is not, and the player comes back with a player
 * file that never heard about the death.
 */
class ChestIntegrityServiceTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private DeadChestConfig config;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        Plugin plugin = MockBukkit.createMockPlugin();

        DeadChestLoader.plugin = plugin;
        DeadChestLoader.log = Logger.getLogger("ChestIntegrityServiceTest");
        DeadChestLoader.local = new Localization();
        TestDatabase.start(plugin);

        config = mock(DeadChestConfig.class);
        when(config.getBoolean(ConfigKey.INTEGRITY_PROTECTION_ENABLED)).thenReturn(true);
        when(config.getBoolean(ConfigKey.INTEGRITY_FLUSH_PLAYER_DATA)).thenReturn(true);
        when(config.getString(ConfigKey.INTEGRITY_ON_ROLLBACK)).thenReturn("void");
        DeadChestLoader.config = config;

        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());
        ChestIntegrityService.resetSessionState();

        player = server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());
        ChestIntegrityService.resetSessionState();
        TestDatabase.stop();
        MockBukkit.unmock();
    }

    @Test
    void deathStagesTheChestAndStampsThePlayer() {
        ChestData chest = trackedChest(10);

        ChestIntegrityService.beginDeath(player, chest);

        assertEquals(ChestIntegrityState.PENDING, chest.getIntegrityState());
        assertEquals(player.getUniqueId(), chest.getIntegrityOwner());
        assertTrue(chest.getIntegritySequence() > 0);
        assertEquals(chest.getIntegritySequence(), PlayerDataStamp.readSequence(player),
                "The player must carry the sequence the chest waits for");
    }

    @Test
    void everyDeathGetsItsOwnSequence() {
        ChestData first = trackedChest(11);
        ChestData second = trackedChest(12);

        ChestIntegrityService.beginDeath(player, first);
        ChestIntegrityService.beginDeath(player, second);

        assertTrue(second.getIntegritySequence() > first.getIntegritySequence());
    }

    @Test
    void flushingThePlayerDataConfirmsTheChest() {
        ChestData chest = trackedChest(13);
        DeadChestLoader.getChestDataCache().addChestData(chest);
        ChestIntegrityService.beginDeath(player, chest);

        int settled = ChestIntegrityService.flushAndSettle(savingPlayer());

        assertEquals(1, settled);
        assertEquals(ChestIntegrityState.CONFIRMED, chest.getIntegrityState());
        assertTrue(chest.isSettled());
    }

    @Test
    void reconcileRemovesTheChestWhenTheDeathWasNeverSavedOnThePlayerSide() {
        ChestData chest = crashedDeath(14);
        world.getBlockAt(chest.getChestLocation()).setType(Material.CHEST);

        ChestIntegrityService.reconcile(player);

        assertNull(DeadChestLoader.getChestDataCache().getChestData(chest.getChestLocation()),
                "A chest holding items the player still owns must not survive");
        assertEquals(Material.AIR, world.getBlockAt(chest.getChestLocation()).getType());
    }

    @Test
    void reconcileKeepsTheChestWhenThePlayerFileCarriesTheDeath() {
        ChestData chest = crashedDeath(15);
        PlayerDataStamp.writeSequence(player, chest.getIntegritySequence());
        world.getBlockAt(chest.getChestLocation()).setType(Material.CHEST);

        ChestIntegrityService.reconcile(player);

        assertSame(chest, DeadChestLoader.getChestDataCache().getChestData(chest.getChestLocation()));
        assertEquals(ChestIntegrityState.CONFIRMED, chest.getIntegrityState());
    }

    @Test
    void aProvenDuplicateIsAlwaysVoided() {
        // Keeping the duplicate left two copies of the same items on the server,
        // which made a crash a way to duplicate on purpose. The option is gone, so
        // a config still carrying it decides nothing.
        // The removal itself runs against a real item entity in LockedDropIntegrityTest :
        // MockBukkit leaves the chest removal path unimplemented, which aborts a test
        // rather than failing it.
        when(config.getString(ConfigKey.INTEGRITY_ON_ROLLBACK)).thenReturn("keep");
        assertTrue(ChestIntegrityService.shouldVoidRollbackDuplicates(), "'keep' is no longer honored");

        when(config.getString(ConfigKey.INTEGRITY_ON_ROLLBACK)).thenReturn("void");
        assertTrue(ChestIntegrityService.shouldVoidRollbackDuplicates());

        when(config.getString(ConfigKey.INTEGRITY_ON_ROLLBACK)).thenReturn(null);
        assertTrue(ChestIntegrityService.shouldVoidRollbackDuplicates(), "A missing value voids too");
    }

    @Test
    void reconcileAlsoCatchesADeathAlreadyConfirmedBeforeTheCrash() {
        // Confirmed in memory, but the player file went back to before the death:
        // the stamp is what proves it, not the state.
        ChestData chest = crashedDeath(17);
        chest.setIntegrityState(ChestIntegrityState.CONFIRMED);
        world.getBlockAt(chest.getChestLocation()).setType(Material.CHEST);

        ChestIntegrityService.reconcile(player);

        assertNull(DeadChestLoader.getChestDataCache().getChestData(chest.getChestLocation()));
    }

    @Test
    void reconcileRestoresAChestWhoseHandOverWasRolledBack() {
        ChestData chest = trackedChest(18);
        DeadChestLoader.getChestDataCache().addChestData(chest);
        ChestDataRepository.save(chest);

        assertTrue(ChestIntegrityService.stageClaim(player, chest));
        // The player never kept the items: the stamp is gone from the player file.
        player.getPersistentDataContainer().remove(
                org.bukkit.NamespacedKey.fromString("deadchest:integrity_seq"));

        ChestIntegrityService.reconcile(player);

        assertSame(chest, DeadChestLoader.getChestDataCache().getChestData(chest.getChestLocation()));
        assertEquals(ChestIntegrityState.CONFIRMED, chest.getIntegrityState());
        assertEquals(1, chest.getInventory().size(), "The content must come back with the chest");
    }

    @Test
    void aHandOverThatReachedThePlayerFileDeletesTheChest() {
        ChestData chest = trackedChest(19);
        DeadChestLoader.getChestDataCache().addChestData(chest);
        ChestDataRepository.save(chest);

        assertTrue(ChestIntegrityService.stageClaim(player, chest));
        ChestIntegrityService.flushAndSettle(savingPlayer());

        assertNull(DeadChestLoader.getChestDataCache().getChestData(chest.getChestLocation()));
        TestDatabase.await();
        assertTrue(ChestDataRepository.findAll().isEmpty(), "The claimed row must be deleted once the player kept the items");
    }

    @Test
    void aHandOverKeepsTheChestWhenThePlayerDataCouldNotBeSaved() {
        ChestData chest = trackedChest(22);
        DeadChestLoader.getChestDataCache().addChestData(chest);
        ChestDataRepository.save(chest);

        assertTrue(ChestIntegrityService.stageClaim(player, chest));
        // The player object refuses to save: nothing proves the items reached the
        // disk, so the chest must stay recoverable instead of being deleted.
        ChestIntegrityService.flushAndSettle(player);

        TestDatabase.await();
        List<ChestData> stored = ChestDataRepository.findAll();
        assertEquals(1, stored.size());
        assertEquals(ChestIntegrityState.CLAIMED, stored.get(0).getIntegrityState());
    }

    /**
     * MockBukkit players refuse an explicit save, this one accepts it like a real
     * server does.
     */
    private PlayerMock savingPlayer() {
        PlayerMock saving = spy(player);
        doNothing().when(saving).saveData();
        return saving;
    }

    @Test
    void stagedStateSurvivesARestart() {
        ChestData chest = trackedChest(20);
        DeadChestLoader.getChestDataCache().addChestData(chest);
        ChestIntegrityService.beginDeath(player, chest);
        assertTrue(ChestDataRepository.saveDurable(chest));

        List<ChestData> reloaded = ChestDataRepository.findAll();

        assertEquals(1, reloaded.size());
        assertEquals(ChestIntegrityState.PENDING, reloaded.get(0).getIntegrityState());
        assertEquals(chest.getIntegritySequence(), reloaded.get(0).getIntegritySequence());
        assertEquals(player.getUniqueId(), reloaded.get(0).getIntegrityOwner());
        assertEquals(chest.getDeathId(), reloaded.get(0).getDeathId());
    }

    @Test
    void protectionDisabledKeepsTheHistoricBehavior() {
        when(config.getBoolean(ConfigKey.INTEGRITY_PROTECTION_ENABLED)).thenReturn(false);
        ChestData chest = trackedChest(21);

        ChestIntegrityService.beginDeath(player, chest);

        assertEquals(ChestIntegrityState.CONFIRMED, chest.getIntegrityState());
        assertTrue(chest.isSettled());
    }

    /**
     * Builds a chest left pending by a crash: the plugin wrote the death, the
     * player file did not.
     */
    private ChestData crashedDeath(int x) {
        ChestData chest = trackedChest(x);
        chest.setIntegrityOwner(player.getUniqueId());
        chest.setIntegritySequence(5L);
        chest.setIntegrityState(ChestIntegrityState.PENDING);
        DeadChestLoader.getChestDataCache().addChestData(chest);
        ChestDataRepository.save(chest);
        return chest;
    }

    private ChestData trackedChest(int x) {
        List<ItemStack> inventory = new ArrayList<>();
        inventory.add(new ItemStack(Material.DIAMOND, 1));

        return new ChestData(
                inventory,
                new Location(world, x, 64, x),
                player.getName(),
                player.getUniqueId(),
                new Date(),
                false,
                false,
                new Location(world, x, 65, x),
                UUID.nameUUIDFromBytes(("timer-" + x).getBytes()),
                UUID.nameUUIDFromBytes(("status-" + x).getBytes()),
                UUID.nameUUIDFromBytes(("owner-" + x).getBytes()),
                "world",
                0
        );
    }
}
