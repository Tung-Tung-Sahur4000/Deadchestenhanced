package me.crylonz.deadchest.listener;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.inventory.InventoryMock;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.Permission;
import me.crylonz.deadchest.drops.LockedDropService;
import me.crylonz.deadchest.utils.DeadChestConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static me.crylonz.deadchest.utils.ConfigKey.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class LockedDropListenerTest {

    private static final long DEATH_TIME = 1_700_000_000_000L;

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
        return lockedDrop(ownerId, expirationTime, DEATH_TIME);
    }

    private Item lockedDrop(UUID ownerId, long expirationTime, long creationTime) {
        Item item = withEntityApi(world.dropItemNaturally(new Location(world, 0, 65, 0), new ItemStack(Material.DIAMOND, 1)));
        LockedDropService.lockDrop(item, ownerId, "Steve", creationTime, expirationTime);
        return item;
    }

    /**
     * MockBukkit item entities throw {@code UnimplementedOperationException} on the
     * owner API, and that exception <em>aborts</em> a test instead of
     * failing it, so an assertion on those calls would silently report as skipped.
     * The spy gives them a working implementation. It shares the persistent data
     * container of the entity it wraps, so the item left in the world stays tagged.
     */
    private Item withEntityApi(Item item) {
        Item entity = spy(item);
        AtomicReference<UUID> nativeOwner = new AtomicReference<>();

        doAnswer(invocation -> {
            nativeOwner.set(invocation.getArgument(0));
            return null;
        }).when(entity).setOwner(any());
        doAnswer(invocation -> nativeOwner.get()).when(entity).getOwner();
        doNothing().when(entity).setCanMobPickup(anyBoolean());

        return entity;
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
    void aReservedDropCarriesTheVanillaOwnerLock() {
        // The server itself must refuse the pickup : the listener is only the
        // second line of defense, it can be defeated by any plugin running later.
        Item item = lockedDrop(owner.getUniqueId(), 0L);

        assertEquals(owner.getUniqueId(), item.getOwner());
    }

    @Test
    void theVanillaOwnerLockIsNotSetWhenPickupIsNotRestricted() {
        when(cfg.getBoolean(VANILLA_DROP_OWNER_ONLY_PICKUP)).thenReturn(false);

        assertNull(lockedDrop(owner.getUniqueId(), 0L).getOwner());
    }

    @Test
    void aDropFoundBackAfterARestartGetsItsVanillaOwnerLockAgain() {
        Item item = lockedDrop(owner.getUniqueId(), System.currentTimeMillis() + 300_000L);
        item.setOwner(null);
        LockedDropService.clearTracking();

        LockedDropService.trackExistingDrop(item);

        assertEquals(owner.getUniqueId(), item.getOwner());
    }

    @Test
    void aBypassPlayerLiftsTheVanillaOwnerLock() {
        // Without lifting it the server would still refuse the pickup right after
        // the listener let the event through, and the permission would do nothing.
        otherPlayer.addAttachment(DeadChestLoader.plugin, Permission.DROP_PASS.label, true);
        Item item = lockedDrop(owner.getUniqueId(), 0L);
        EntityPickupItemEvent event = new EntityPickupItemEvent(otherPlayer, item, 0);

        listener.onEntityPickupItem(event);

        assertFalse(event.isCancelled());
        assertNull(item.getOwner());
    }

    @Test
    void theOwnerPickupKeepsTheVanillaOwnerLockInPlace() {
        Item item = lockedDrop(owner.getUniqueId(), 0L);
        EntityPickupItemEvent event = new EntityPickupItemEvent(owner, item, 0);

        listener.onEntityPickupItem(event);

        assertFalse(event.isCancelled());
        assertEquals(owner.getUniqueId(), item.getOwner());
    }

    @Test
    void mobsCanLootAReservedDropWhenPickupIsNotRestricted() {
        // 'owner-only-pickup' set to false documents that anybody can take the
        // drops, which the mob branch used to ignore.
        when(cfg.getBoolean(VANILLA_DROP_OWNER_ONLY_PICKUP)).thenReturn(false);
        Zombie zombie = (Zombie) world.spawnEntity(new Location(world, 0, 65, 0), EntityType.ZOMBIE);
        EntityPickupItemEvent event = new EntityPickupItemEvent(zombie, lockedDrop(owner.getUniqueId(), 0L), 0);

        listener.onEntityPickupItem(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void mobsNeverLootAReservedDropWhenPickupIsRestricted() {
        Zombie zombie = (Zombie) world.spawnEntity(new Location(world, 0, 65, 0), EntityType.ZOMBIE);
        EntityPickupItemEvent event = new EntityPickupItemEvent(zombie, lockedDrop(owner.getUniqueId(), 0L), 0);

        listener.onEntityPickupItem(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void theProtectionHandlersKeepTheLastWordOnTheEvent() throws NoSuchMethodException {
        // A protection running early can be undone by any plugin listening after
        // it, which silently turns the lock off on a server that has one.
        assertEquals(EventPriority.HIGHEST, priorityOf("onEntityPickupItem", EntityPickupItemEvent.class));
        assertEquals(EventPriority.HIGHEST, priorityOf("onInventoryPickupItem", InventoryPickupItemEvent.class));
        assertEquals(EventPriority.HIGHEST, priorityOf("onItemDespawn", ItemDespawnEvent.class));
        assertEquals(EventPriority.HIGHEST, priorityOf("onItemMerge", ItemMergeEvent.class));
    }

    private EventPriority priorityOf(String method, Class<?> eventType) throws NoSuchMethodException {
        return LockedDropListener.class.getMethod(method, eventType).getAnnotation(EventHandler.class).priority();
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
    void vanillaDespawnStillAppliesWhenTheProtectionIsTurnedOff() {
        // 'protect-from-despawn' set to false documents that the vanilla timer
        // keeps the last word, whichever of the two comes first.
        when(cfg.getBoolean(VANILLA_DROP_PROTECT_FROM_DESPAWN)).thenReturn(false);
        Item item = lockedDrop(owner.getUniqueId(), System.currentTimeMillis() + 300_000L);
        ItemDespawnEvent event = new ItemDespawnEvent(item, item.getLocation());

        listener.onItemDespawn(event);

        assertFalse(event.isCancelled());
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
    void dropsOfTheSameDeathStillMerge() {
        Item source = lockedDrop(owner.getUniqueId(), 0L, DEATH_TIME);
        Item target = lockedDrop(owner.getUniqueId(), 0L, DEATH_TIME);
        ItemMergeEvent event = new ItemMergeEvent(source, target);

        listener.onItemMerge(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void twoDeathsOfTheSameOwnerNeverMerge() {
        // The merged stack would keep one set of tags only, so the other death
        // would inherit its lifetime and its crash protection stamp.
        Item source = lockedDrop(owner.getUniqueId(), 0L, DEATH_TIME);
        Item target = lockedDrop(owner.getUniqueId(), 0L, DEATH_TIME + 60_000L);
        ItemMergeEvent event = new ItemMergeEvent(source, target);

        listener.onItemMerge(event);

        assertTrue(event.isCancelled());
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
