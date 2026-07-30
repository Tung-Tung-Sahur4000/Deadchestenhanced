package me.crylonz.deadchest.drops;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Localization;
import me.crylonz.deadchest.Permission;
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
import java.util.UUID;
import java.util.logging.Logger;

import static me.crylonz.deadchest.utils.ConfigKey.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LockedDropServiceTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private DeadChestConfig cfg;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new WorldMock(Material.GRASS_BLOCK, 64);
        server.addWorld(world);

        world.loadChunk(0, 0);

        player = server.addPlayer("Steve");
        player.teleport(new Location(world, 0, 65, 0));

        DeadChestLoader.plugin = MockBukkit.createMockPlugin();
        DeadChestLoader.log = Logger.getLogger("DeadChestTest");
        DeadChestLoader.local = new Localization();

        cfg = mock(DeadChestConfig.class);
        when(cfg.getBoolean(VANILLA_DROP_ENABLED)).thenReturn(true);
        when(cfg.getBoolean(VANILLA_DROP_OWNER_ONLY_PICKUP)).thenReturn(true);
        when(cfg.getBoolean(VANILLA_DROP_PROTECT_FROM_DESPAWN)).thenReturn(true);
        when(cfg.getBoolean(VANILLA_DROP_INVULNERABLE)).thenReturn(false);
        when(cfg.getBoolean(VANILLA_DROP_GLOW)).thenReturn(false);
        when(cfg.getBoolean(DISPLAY_POSITION_ON_DEATH)).thenReturn(false);
        when(cfg.getBoolean(LOG_DEADCHEST_ON_CONSOLE)).thenReturn(false);
        when(cfg.getInt(VANILLA_DROP_DESPAWN_SECONDS)).thenReturn(300);
        when(cfg.getIgnoredEntries()).thenReturn(new ArrayList<>());

        DeadChestLoader.config = cfg;
        LockedDropService.clearTracking();
    }

    @AfterEach
    void tearDown() {
        LockedDropService.clearTracking();
        MockBukkit.unmock();
    }

    private PlayerDeathEvent deathEvent(ItemStack... drops) {
        List<ItemStack> dropList = new ArrayList<>();
        Collections.addAll(dropList, drops);
        return new PlayerDeathEvent(player, dropList, 0, "");
    }

    private Item dropOf(UUID ownerId, long expirationTime) {
        Item item = world.dropItemNaturally(new Location(world, 0, 65, 0), new ItemStack(Material.DIAMOND, 1));
        LockedDropService.lockDrop(item, ownerId, "Steve", System.currentTimeMillis(), expirationTime);
        return item;
    }

    @Test
    void deathDropsAreSpawnedOnTheGroundAndLockedToTheDeadPlayer() {
        PlayerDeathEvent event = deathEvent(new ItemStack(Material.DIAMOND, 3), new ItemStack(Material.IRON_INGOT, 5));

        LockedDropService.handlePlayerDeath(event, player);

        assertTrue(event.getDrops().isEmpty(), "Drops are taken over by the plugin");
        assertEquals(2, LockedDropService.getTrackedDropAmount(), "Both stacks are tracked");

        List<Item> items = new ArrayList<>(world.getEntitiesByClass(Item.class));
        assertEquals(2, items.size(), "Both stacks are on the ground");
        for (Item item : items) {
            assertEquals(player.getUniqueId(), LockedDropService.getOwner(item));
            assertTrue(LockedDropService.isLocked(item));
        }
    }

    @Test
    void noChestNoDropWhenNothingIsDroppable() {
        PlayerDeathEvent event = deathEvent();

        LockedDropService.handlePlayerDeath(event, player);

        assertEquals(0, LockedDropService.getTrackedDropAmount());
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
    }

    @Test
    void ignoredItemsKeepTheirVanillaDeathBehavior() {
        when(cfg.getIgnoredEntries()).thenReturn(new ArrayList<Object>(Collections.singletonList("DIAMOND")));
        PlayerDeathEvent event = deathEvent(new ItemStack(Material.DIAMOND, 1), new ItemStack(Material.IRON_INGOT, 1));

        LockedDropService.handlePlayerDeath(event, player);

        assertEquals(1, event.getDrops().size(), "Ignored item stays a regular vanilla drop");
        assertEquals(Material.DIAMOND, event.getDrops().get(0).getType());
        assertEquals(1, LockedDropService.getTrackedDropAmount(), "Only the non ignored item is locked");
    }

    @Test
    void expirationUsesTheConfiguredLifetime() {
        when(cfg.getInt(VANILLA_DROP_DESPAWN_SECONDS)).thenReturn(300);
        assertEquals(1_000_300_000L, LockedDropService.computeExpirationTime(1_000_000_000L));

        when(cfg.getInt(VANILLA_DROP_DESPAWN_SECONDS)).thenReturn(0);
        assertEquals(0L, LockedDropService.computeExpirationTime(1_000_000_000L), "0 second means no expiration");
    }

    @Test
    void ownerCanPickUpButOtherPlayersCannot() {
        Item item = dropOf(player.getUniqueId(), 0L);
        PlayerMock otherPlayer = server.addPlayer("Alex");

        assertTrue(LockedDropService.canPickUp(player, item));
        assertFalse(LockedDropService.canPickUp(otherPlayer, item));
        assertFalse(LockedDropService.canPickUp(null, item), "Mobs never loot a reserved drop");
    }

    @Test
    void bypassPermissionAllowsAnotherPlayerToPickUp() {
        Item item = dropOf(player.getUniqueId(), 0L);
        PlayerMock otherPlayer = server.addPlayer("Alex");
        otherPlayer.addAttachment(DeadChestLoader.plugin, Permission.DROP_PASS.label, true);

        assertTrue(LockedDropService.canPickUp(otherPlayer, item));
    }

    @Test
    void everybodyCanPickUpWhenOwnerOnlyPickupIsDisabled() {
        Item item = dropOf(player.getUniqueId(), 0L);
        PlayerMock otherPlayer = server.addPlayer("Alex");
        when(cfg.getBoolean(VANILLA_DROP_OWNER_ONLY_PICKUP)).thenReturn(false);

        assertTrue(LockedDropService.canPickUp(otherPlayer, item));
    }

    @Test
    void regularDropsAreNotAffected() {
        Item item = world.dropItemNaturally(new Location(world, 0, 65, 0), new ItemStack(Material.DIRT, 1));
        PlayerMock otherPlayer = server.addPlayer("Alex");

        assertFalse(LockedDropService.isLocked(item));
        assertNull(LockedDropService.getOwner(item));
        assertTrue(LockedDropService.canPickUp(otherPlayer, item));
    }

    @Test
    void expiredDropIsRemovedByMaintenance() {
        Item item = dropOf(player.getUniqueId(), System.currentTimeMillis() - 1_000L);

        LockedDropService.tick();

        assertFalse(item.isValid(), "Expired drop is removed from the world");
        assertEquals(0, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void maintenanceKeepsTheDropUntilItsOwnLifetimeIsReached() {
        Item item = dropOf(player.getUniqueId(), System.currentTimeMillis() + 300_000L);

        LockedDropService.tick();

        assertTrue(item.isValid(), "Drop is kept until its own lifetime is reached");
        assertEquals(1, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void despawnRefreshResetsTheVanillaAgeOfTheItem() {
        // MockBukkit item entities do not implement the age API, so the reset is
        // checked on a plain Bukkit item.
        Item item = mock(Item.class);

        LockedDropService.refreshDespawnTimer(item);

        verify(item).setTicksLived(1);
    }

    @Test
    void maintenanceForgetsDropsThatAreGone() {
        Item item = dropOf(player.getUniqueId(), 0L);
        item.remove();

        LockedDropService.tick();

        assertEquals(0, LockedDropService.getTrackedDropAmount());
    }

    @Test
    void alreadyTaggedDropIsTrackedAgainAfterAReload() {
        Item item = dropOf(player.getUniqueId(), System.currentTimeMillis() + 300_000L);
        LockedDropService.clearTracking();

        LockedDropService.trackExistingDrop(item);

        assertEquals(1, LockedDropService.getTrackedDropAmount());
        assertEquals(player.getUniqueId(), LockedDropService.getOwner(item));
    }

    @Test
    void invulnerabilityAndGlowFollowTheConfiguration() {
        when(cfg.getBoolean(VANILLA_DROP_INVULNERABLE)).thenReturn(true);
        when(cfg.getBoolean(VANILLA_DROP_GLOW)).thenReturn(true);

        Item item = dropOf(player.getUniqueId(), 0L);

        assertTrue(item.isInvulnerable());
        assertTrue(item.isGlowing());
    }

    @Test
    void deathPositionIsStillAnnouncedToThePlayer() {
        when(cfg.getBoolean(DISPLAY_POSITION_ON_DEATH)).thenReturn(true);

        LockedDropService.handlePlayerDeath(deathEvent(new ItemStack(Material.DIAMOND, 1)), player);

        assertNotNull(player.nextMessage(), "Player is told where the items dropped");
    }
}
