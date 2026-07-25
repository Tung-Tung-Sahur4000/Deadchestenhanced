package me.crylonz.deadchest.listener;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.block.BlockMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.FileManager;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.TestDatabase;
import me.crylonz.deadchest.db.InMemoryChestStore;
import me.crylonz.deadchest.integrity.ChestIntegrityState;
import me.crylonz.deadchest.utils.ConfigKey;
import me.crylonz.deadchest.utils.DeadChestConfig;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DeadChest main interaction listener.
 * Covers inventory restore, drop mode, permission handling,
 * event cancellation and cleanup.
 */
class ClickListenerTest {

    private WorldMock world;
    private PlayerMock player;
    private BlockMock chestBlock;
    private ClickListener listener;
    private InMemoryChestStore deadChest;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        world = new WorldMock();
        MockBukkit.getMock().addWorld(world);
        player = new PlayerMock(MockBukkit.getMock(), "Steve");
        chestBlock = world.getBlockAt(0, 64, 0);
        chestBlock.setType(Material.CHEST);
        DeadChestLoader.graveBlocks.add(Material.CHEST);

        deadChest = DeadChestLoader.getChestDataCache();
        deadChest.setChestData(new ArrayList<>());

        DeadChestLoader.plugin = MockBukkit.createMockPlugin();
        DeadChestLoader.log = java.util.logging.Logger.getLogger("ClickListenerTest");
        TestDatabase.start(DeadChestLoader.plugin);

        DeadChestLoader.local = mock(Localization.class);
        DeadChestLoader.fileManager = mock(FileManager.class);
        when(DeadChestLoader.local.prefixed("chest.not-owner")).thenReturn("[DC] You are not the owner");
        when(DeadChestLoader.local.prefixed("chest.no-permission-open")).thenReturn("[DC] You cannot take this chest");

        DeadChestLoader.config = mock(DeadChestConfig.class);
        listener = new ClickListener();
    }

    @AfterEach
    void tearDown() {
        TestDatabase.stop();
        MockBukkit.unmock();
    }

    /**
     * Chest mock already reconciled with the player data, which is the state of
     * every chest a player can interact with.
     */
    private ChestData mockChest() {
        ChestData chestData = mock(ChestData.class);
        when(chestData.isSettled()).thenReturn(true);
        when(chestData.beginTransfer()).thenReturn(true);
        when(chestData.getIntegrityState()).thenReturn(ChestIntegrityState.CONFIRMED);
        when(chestData.getDeathId()).thenReturn(UUID.randomUUID());
        return chestData;
    }

    @Test
    void testIsNearGraveChest_CancelsEvent() {
        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.STONE), chestBlock, BlockFace.UP);

        listener.onClick(event);
        assertTrue(event.isCancelled(), "Right click near DeadChest should cancel the event");
    }

    @Test
    void testClickOnDeadChest_NotOwner_Denied() {
        // Config requires ownership
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(true);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.getPlayerUUID()).thenReturn(UUID.randomUUID());
        deadChest.addChestData(cd);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertTrue(event.isCancelled(), "Event should be cancelled for non-owner");
        assertEquals("[DC] You are not the owner", player.nextMessage());
    }

    @Test
    void testClickOnDeadChest_Owner_RestoreInventory() {
        // Config: owner can open, drop mode = 1 (restore)
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.REQUIRE_PERMISSION_TO_GET_CHEST)).thenReturn(false);
        when(DeadChestLoader.config.getInt(ConfigKey.DROP_MODE)).thenReturn(1);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.getPlayerUUID()).thenReturn(player.getUniqueId());
        when(cd.getInventory()).thenReturn(Arrays.asList(new ItemStack(Material.DIAMOND), new ItemStack(Material.APPLE)));
        when(cd.getXpStored()).thenReturn(5);
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        PlayerInventory inv = player.getInventory();
        assertTrue(inv.contains(Material.DIAMOND), "Diamond should be restored to player inventory");
        assertTrue(inv.contains(Material.APPLE), "Apple should be restored to player inventory");
        assertEquals(Material.AIR, chestBlock.getType(), "Chest block should be removed after pickup");
    }

    @Test
    void testClickOnDeadChest_DropModeDropsItems() {
        // Config: drop mode = 2
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(false);
        when(DeadChestLoader.config.getInt(ConfigKey.DROP_MODE)).thenReturn(2);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.getInventory()).thenReturn(List.of(new ItemStack(Material.EMERALD)));
        when(cd.getXpStored()).thenReturn(10);
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertEquals(Material.AIR, chestBlock.getType(), "Chest should be removed in drop mode");
        // World drops are not directly testable in MockBukkit (but you can spy WorldMock if needed)
    }

    @Test
    void testClickOnDeadChest_UnreconciledChestStaysClosed() {
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(false);
        when(DeadChestLoader.config.getInt(ConfigKey.DROP_MODE)).thenReturn(1);
        when(DeadChestLoader.local.prefixed("chest.not-reconciled")).thenReturn("[DC] Checking after a crash");

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.isSettled()).thenReturn(false);
        when(cd.getInventory()).thenReturn(List.of(new ItemStack(Material.DIAMOND)));
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertTrue(event.isCancelled(), "A chest awaiting reconciliation must not open");
        assertEquals("[DC] Checking after a crash", player.nextMessage());
        assertEquals(Material.CHEST, chestBlock.getType(), "The chest must stay in place");
        assertTrue(player.getInventory().isEmpty(), "No item may be handed over before reconciliation");
    }

    @Test
    void testClickOnDeadChest_ContentIsHandedOverOnlyOnce() {
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(false);
        when(DeadChestLoader.config.getInt(ConfigKey.DROP_MODE)).thenReturn(1);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        // Another interaction already took the hand over.
        when(cd.beginTransfer()).thenReturn(false);
        when(cd.getInventory()).thenReturn(List.of(new ItemStack(Material.DIAMOND)));
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertTrue(event.isCancelled());
        assertTrue(player.getInventory().isEmpty(), "The same content must never be given twice");
    }

    @Test
    void testHasGetPermission_Denied() {
        when(DeadChestLoader.config.getBoolean(ConfigKey.REQUIRE_PERMISSION_TO_GET_CHEST)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(false);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.getPlayerUUID()).thenReturn(player.getUniqueId());
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertTrue(event.isCancelled(), "Event should be cancelled when player lacks GET permission");
        assertEquals("[DC] You cannot take this chest", player.nextMessage());
    }

    @Test
    void testClickOnDeadChest_DuringPublicPhase_OtherPlayerCanLoot() {
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.REQUIRE_PERMISSION_TO_GET_CHEST)).thenReturn(false);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_ENABLED)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_PUBLIC_ACCESS_OTHER_PLAYERS)).thenReturn(true);
        when(DeadChestLoader.config.getInt(ConfigKey.DEADCHEST_DURATION)).thenReturn(1);
        when(DeadChestLoader.config.getInt(ConfigKey.LOOT_PUBLIC_DURATION)).thenReturn(30);
        when(DeadChestLoader.config.getInt(ConfigKey.DROP_MODE)).thenReturn(1);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.getPlayerUUID()).thenReturn(UUID.randomUUID());
        when(cd.getChestDate()).thenReturn(new Date(System.currentTimeMillis() - 10_000L));
        when(cd.isInfinity()).thenReturn(false);
        when(cd.getInventory()).thenReturn(List.of(new ItemStack(Material.DIAMOND)));
        when(cd.getXpStored()).thenReturn(0);
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertTrue(player.getInventory().contains(Material.DIAMOND), "Public phase chest should be lootable by other players");
        assertEquals(Material.AIR, chestBlock.getType(), "Looted public chest should be removed");
    }

    @Test
    void testClickOnDeadChest_DuringPublicPhase_KillerCanLootWhenEnabled() {
        PlayerMock killer = new PlayerMock(MockBukkit.getMock(), "Alex");
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.REQUIRE_PERMISSION_TO_GET_CHEST)).thenReturn(false);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_ENABLED)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_PUBLIC_ACCESS_OWNER)).thenReturn(false);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_PUBLIC_ACCESS_KILLER)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_PUBLIC_ACCESS_OTHER_PLAYERS)).thenReturn(false);
        when(DeadChestLoader.config.getInt(ConfigKey.DEADCHEST_DURATION)).thenReturn(1);
        when(DeadChestLoader.config.getInt(ConfigKey.LOOT_PUBLIC_DURATION)).thenReturn(30);
        when(DeadChestLoader.config.getInt(ConfigKey.DROP_MODE)).thenReturn(1);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.getPlayerUUID()).thenReturn(UUID.randomUUID());
        when(cd.getKillerUUID()).thenReturn(killer.getUniqueId());
        when(cd.getChestDate()).thenReturn(new Date(System.currentTimeMillis() - 10_000L));
        when(cd.isInfinity()).thenReturn(false);
        when(cd.getInventory()).thenReturn(List.of(new ItemStack(Material.EMERALD)));
        when(cd.getXpStored()).thenReturn(0);
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(killer, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertTrue(killer.getInventory().contains(Material.EMERALD), "Configured killer should be able to loot during public phase");
    }

    @Test
    void testClickOnDeadChest_DuringPublicPhase_OtherPlayerDeniedWhenDisabled() {
        when(DeadChestLoader.config.getBoolean(ConfigKey.ONLY_OWNER_CAN_OPEN_CHEST)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_ENABLED)).thenReturn(true);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_PUBLIC_ACCESS_OTHER_PLAYERS)).thenReturn(false);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_PUBLIC_ACCESS_KILLER)).thenReturn(false);
        when(DeadChestLoader.config.getBoolean(ConfigKey.LOOT_PUBLIC_ACCESS_OWNER)).thenReturn(true);
        when(DeadChestLoader.config.getInt(ConfigKey.DEADCHEST_DURATION)).thenReturn(1);
        when(DeadChestLoader.config.getInt(ConfigKey.LOOT_PUBLIC_DURATION)).thenReturn(30);

        ChestData cd = mockChest();
        when(cd.getChestLocation()).thenReturn(chestBlock.getLocation());
        when(cd.getPlayerUUID()).thenReturn(UUID.randomUUID());
        when(cd.getKillerUUID()).thenReturn(UUID.randomUUID());
        when(cd.getChestDate()).thenReturn(new Date(System.currentTimeMillis() - 10_000L));
        when(cd.isInfinity()).thenReturn(false);
        deadChest.addChestData(cd);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                new ItemStack(Material.CHEST), chestBlock, BlockFace.UP);

        listener.onClick(event);

        assertTrue(event.isCancelled(), "Other player should be denied when public access is disabled");
        assertEquals("[DC] You are not the owner", player.nextMessage());
    }
}
