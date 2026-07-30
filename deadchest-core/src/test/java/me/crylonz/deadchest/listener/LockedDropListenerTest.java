package me.crylonz.deadchest.listener;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.inventory.InventoryMock;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.drops.LockedDropService;
import me.crylonz.deadchest.utils.DeadChestConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import static me.crylonz.deadchest.utils.ConfigKey.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LockedDropListenerTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private PlayerMock otherPlayer;
    private DeadChestConfig cfg;
    private LockedDropListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new WorldMock(Material.GRASS_BLOCK, 64);
        server.addWorld(world);
        world.loadChunk(0, 0);

        owner = server.addPlayer("Steve");
        otherPlayer = server.addPlayer("Alex");

        DeadChestLoader.plugin = MockBukkit.createMockPlugin();
        DeadChestLoader.log = Logger.getLogger("DeadChestTest");
        DeadChestLoader.local = new Localization();

        cfg = mock(DeadChestConfig.class);
        when(cfg.getBoolean(VANILLA_DROP_ENABLED)).thenReturn(true);
        when(cfg.getBoolean(VANILLA_DROP_OWNER_ONLY_PICKUP)).thenReturn(true);
        when(cfg.getBoolean(VANILLA_DROP_PROTECT_FROM_DESPAWN)).thenReturn(true);
        when(cfg.getBoolean(VANILLA_DROP_INVULNERABLE)).thenReturn(false);
        when(cfg.getBoolean(VANILLA_DROP_GLOW)).thenReturn(false);
        when(cfg.getInt(VANILLA_DROP_DESPAWN_SECONDS)).thenReturn(300);
        when(cfg.getIgnoredEntries()).thenReturn(new ArrayList<>());
        DeadChestLoader.config = cfg;

        LockedDropService.clearTracking();
        listener = new LockedDropListener();
    }

    @AfterEach
    void tearDown() {
        LockedDropService.clearTracking();
        MockBukkit.unmock();
    }

    private Item lockedDrop(UUID ownerId, long expirationTime) {
        Item item = world.dropItemNaturally(new Location(world, 0, 65, 0), new ItemStack(Material.DIAMOND, 1));
        LockedDropService.lockDrop(item, ownerId, "Steve", System.currentTimeMillis(), expirationTime);
        return item;
    }

    private Item regularDrop() {
        return world.dropItemNaturally(new Location(world, 0, 65, 0), new ItemStack(Material.DIRT, 1));
    }

    @Test
    void ownerPickupIsAllowed() {
        EntityPickupItemEvent event = new EntityPickupItemEvent(owner, lockedDrop(owner.getUniqueId(), 0L), 0);

        listener.onEntityPickupItem(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void otherPlayerPickupIsBlocked() {
        EntityPickupItemEvent event = new EntityPickupItemEvent(otherPlayer, lockedDrop(owner.getUniqueId(), 0L), 0);

        listener.onEntityPickupItem(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void regularDropPickupIsNeverBlocked() {
        EntityPickupItemEvent event = new EntityPickupItemEvent(otherPlayer, regularDrop(), 0);

        listener.onEntityPickupItem(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void hoppersCannotEmptyAReservedDrop() {
        InventoryPickupItemEvent event = new InventoryPickupItemEvent(
                new InventoryMock(null, InventoryType.HOPPER), lockedDrop(owner.getUniqueId(), 0L));

        listener.onInventoryPickupItem(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void hoppersStillCollectRegularDrops() {
        InventoryPickupItemEvent event = new InventoryPickupItemEvent(
                new InventoryMock(null, InventoryType.HOPPER), regularDrop());

        listener.onInventoryPickupItem(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void vanillaDespawnIsCancelledWhileTheDropIsStillReserved() {
        Item item = lockedDrop(owner.getUniqueId(), System.currentTimeMillis() + 300_000L);
        ItemDespawnEvent event = new ItemDespawnEvent(item, item.getLocation());

        listener.onItemDespawn(event);

        assertTrue(event.isCancelled(), "The vanilla 5 minutes timer must not remove a reserved drop");
    }

    @Test
    void despawnIsAllowedOnceTheLifetimeIsOver() {
        Item item = lockedDrop(owner.getUniqueId(), System.currentTimeMillis() - 1_000L);
        ItemDespawnEvent event = new ItemDespawnEvent(item, item.getLocation());

        listener.onItemDespawn(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void dropsOfTwoDifferentOwnersNeverMerge() {
        Item source = lockedDrop(owner.getUniqueId(), 0L);
        Item target = lockedDrop(otherPlayer.getUniqueId(), 0L);
        ItemMergeEvent event = new ItemMergeEvent(source, target);

        listener.onItemMerge(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void reservedDropDoesNotMergeIntoARegularDrop() {
        ItemMergeEvent event = new ItemMergeEvent(lockedDrop(owner.getUniqueId(), 0L), regularDrop());

        listener.onItemMerge(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void dropsOfTheSameOwnerStillMerge() {
        Item source = lockedDrop(owner.getUniqueId(), 0L);
        Item target = lockedDrop(owner.getUniqueId(), 0L);
        ItemMergeEvent event = new ItemMergeEvent(source, target);

        listener.onItemMerge(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void chunkLoadTracksReservedDropsAgain() {
        lockedDrop(owner.getUniqueId(), System.currentTimeMillis() + 300_000L);
        LockedDropService.clearTracking();

        listener.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));

        assertEquals(1, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void entitiesLoadTracksReservedDropsAgain() {
        Item item = lockedDrop(owner.getUniqueId(), System.currentTimeMillis() + 300_000L);
        LockedDropService.clearTracking();

        new LockedDropEntitiesListener().onEntitiesLoad(
                new EntitiesLoadEvent(world.getChunkAt(0, 0), Collections.<Entity>singletonList(item)));

        assertEquals(1, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void entitiesLoadIgnoresRegularDrops() {
        new LockedDropEntitiesListener().onEntitiesLoad(
                new EntitiesLoadEvent(world.getChunkAt(0, 0), Collections.<Entity>singletonList(regularDrop())));

        assertEquals(0, LockedDropService.getTrackedDropAmount());
    }
}
