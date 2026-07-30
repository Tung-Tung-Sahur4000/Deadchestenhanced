package me.crylonz.deadchest.placement;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.utils.ConfigKey;
import me.crylonz.deadchest.utils.DeadChestConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
 * Covers the grave placement chain: one rule per death context, then the checks
 * that make the resolved block actually usable.
 */
class GraveLocationResolverTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private DeadChestConfig config;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // Empty world with modern build limits: every test sets the blocks it needs.
        world = new WorldMock(Material.AIR, -64, 320, 0);
        server.addWorld(world);

        DeadChestLoader.plugin = MockBukkit.createMockPlugin();
        DeadChestLoader.log = Logger.getLogger("GraveLocationResolverTest");
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());
        GroundTracker.reset();

        config = mock(DeadChestConfig.class);
        when(config.getBoolean(ConfigKey.PLACEMENT_GROUND)).thenReturn(true);
        when(config.getBoolean(ConfigKey.PLACEMENT_VOID)).thenReturn(true);
        when(config.getBoolean(ConfigKey.PLACEMENT_LAVA_TOP)).thenReturn(true);
        when(config.getBoolean(ConfigKey.PLACEMENT_LAVA_SMART)).thenReturn(true);
        when(config.getBoolean(ConfigKey.PLACEMENT_WATER_TOP)).thenReturn(false);
        when(config.getBoolean(ConfigKey.PLACEMENT_WATER_BOTTOM)).thenReturn(true);
        when(config.getBoolean(ConfigKey.PLACEMENT_SUFFOCATION)).thenReturn(true);
        when(config.getBoolean(ConfigKey.PLACEMENT_POWDER_SNOW)).thenReturn(true);
        when(config.getBoolean(ConfigKey.PLACEMENT_SAFE_LOCATION)).thenReturn(false);
        when(config.getInt(ConfigKey.PLACEMENT_SEARCH_RADIUS)).thenReturn(6);
        DeadChestLoader.config = config;

        player = server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        DeadChestLoader.getChestDataCache().setChestData(new ArrayList<>());
        GroundTracker.reset();
        MockBukkit.unmock();
    }

    @Test
    void aDeathOnSolidGroundKeepsThePosition() {
        fill(10, 60, 10, Material.STONE);
        clearColumn(10, 61, 80, 10);
        Location death = at(10, 61, 10);

        Location grave = GraveLocationResolver.resolve(player, death);

        assertEquals(death, grave);
    }

    @Test
    void aDeathInTheAirDropsTheGraveToTheGround() {
        fill(11, 60, 11, Material.STONE);
        clearColumn(11, 61, 80, 11);

        Location grave = GraveLocationResolver.resolve(player, at(11, 78, 11));

        assertNotNull(grave);
        assertEquals(61, grave.getBlockY(), "The grave follows the entity down to the first solid block");
    }

    @Test
    void aDeathInTheAirStaysInPlaceWhenTheGroundRuleIsOff() {
        when(config.getBoolean(ConfigKey.PLACEMENT_GROUND)).thenReturn(false);
        fill(12, 60, 12, Material.STONE);
        clearColumn(12, 61, 80, 12);

        Location grave = GraveLocationResolver.resolve(player, at(12, 78, 12));

        assertNotNull(grave);
        assertEquals(78, grave.getBlockY());
    }

    @Test
    void drowningSinksTheGraveToTheBottomOfTheWater() {
        fill(13, 59, 13, Material.STONE);
        fillColumn(13, 60, 70, 13, Material.WATER);
        clearColumn(13, 71, 80, 13);
        damagedBy(EntityDamageEvent.DamageCause.DROWNING);

        Location grave = GraveLocationResolver.resolve(player, at(13, 68, 13));

        assertNotNull(grave);
        assertEquals(60, grave.getBlockY(), "The grave rests on the floor of the water column");
    }

    @Test
    void drowningFloatsTheGraveWhenWaterTopIsEnabled() {
        when(config.getBoolean(ConfigKey.PLACEMENT_WATER_TOP)).thenReturn(true);
        fill(14, 59, 14, Material.STONE);
        fillColumn(14, 60, 70, 14, Material.WATER);
        clearColumn(14, 71, 80, 14);
        damagedBy(EntityDamageEvent.DamageCause.DROWNING);

        Location grave = GraveLocationResolver.resolve(player, at(14, 62, 14));

        assertNotNull(grave);
        assertEquals(71, grave.getBlockY(), "The grave floats on top of the water column");
    }

    @Test
    void aDeathInLavaFloatsTheGraveToTheSurface() {
        fill(15, 59, 15, Material.STONE);
        fillColumn(15, 60, 64, 15, Material.LAVA);
        clearColumn(15, 65, 80, 15);

        Location grave = GraveLocationResolver.resolve(player, at(15, 61, 15));

        assertNotNull(grave);
        assertEquals(65, grave.getBlockY(), "The grave floats on top of the lava column");
    }

    @Test
    void aCoveredLavaPoolFallsBackToTheLastBlockTheEntityStoodOn() {
        fill(16, 59, 16, Material.STONE);
        fillColumn(16, 60, 64, 16, Material.LAVA);
        world.getBlockAt(16, 65, 16).setType(Material.STONE); // lava is covered

        // Standing safely next to the pool before falling in.
        fill(20, 60, 20, Material.STONE);
        world.getBlockAt(20, 61, 20).setType(Material.AIR);
        player.teleport(at(20, 61, 20));
        GroundTracker.track(player);

        Location grave = GraveLocationResolver.resolve(player, at(16, 61, 16));

        assertNotNull(grave);
        assertEquals(at(20, 61, 20), grave, "The grave goes back to the last safe ground");
    }

    @Test
    void aDeathInTheVoidUsesTheClosestRealBlock() {
        fill(17, 60, 17, Material.STONE);
        clearColumn(17, 61, 80, 17);
        damagedBy(EntityDamageEvent.DamageCause.VOID);

        Location grave = GraveLocationResolver.resolve(player, at(17, -20, 17));

        assertNotNull(grave);
        assertTrue(grave.getBlockY() >= world.getMinHeight(), "The grave cannot stay outside the world");
        assertEquals(61, grave.getBlockY());
    }

    @Test
    void suffocatingInsideABlockUsesTheLastGroundThenTheColumn() {
        fillColumn(18, 60, 70, 18, Material.STONE);
        clearColumn(18, 71, 80, 18);
        damagedBy(EntityDamageEvent.DamageCause.SUFFOCATION);

        Location grave = GraveLocationResolver.resolve(player, at(18, 65, 18));

        assertNotNull(grave);
        assertEquals(71, grave.getBlockY(), "The first free block above the suffocation column is used");
    }

    @Test
    void aDeathInPowderSnowPlacesTheGraveOnTheSurface() {
        Material powderSnow = Material.getMaterial("POWDER_SNOW");
        assumeMaterial(powderSnow);

        fill(19, 60, 19, Material.STONE);
        fillColumn(19, 61, 63, 19, powderSnow);
        clearColumn(19, 64, 80, 19);

        Location grave = GraveLocationResolver.resolve(player, at(19, 62, 19));

        assertNotNull(grave);
        assertEquals(64, grave.getBlockY(), "The grave sits above the powder snow surface");
    }

    @Test
    void twoDeathsOnTheSameBlockNeverShareTheGravePosition() {
        fill(21, 60, 21, Material.STONE);
        clearColumn(21, 61, 80, 21);
        final Location death = at(21, 61, 21);

        Location first = GraveLocationResolver.resolve(player, death);
        assertNotNull(first);
        DeadChestLoader.getChestDataCache().addChestData(chestAt(first));

        Player other = server.addPlayer("Alex");
        Location second = GraveLocationResolver.resolve(other, death);

        assertNotNull(second, "The second grave must find another block");
        assertNotEquals(first, second);
    }

    @Test
    void aProtectedPositionPushesTheGraveOutsideTheRegion() {
        when(config.getBoolean(ConfigKey.PLACEMENT_SAFE_LOCATION)).thenReturn(true);
        fill(22, 60, 22, Material.STONE);
        clearColumn(22, 61, 80, 22);

        final Location protectedBlock = at(22, 61, 22);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlace(BlockPlaceEvent event) {
                if (event.getBlock().getX() == protectedBlock.getBlockX()
                        && event.getBlock().getZ() == protectedBlock.getBlockZ()) {
                    event.setCancelled(true);
                }
            }
        }, DeadChestLoader.plugin);

        Location grave = GraveLocationResolver.resolve(player, protectedBlock);

        assertNotNull(grave);
        assertNotEquals(protectedBlock, grave, "A protected block cannot hold the grave");
    }

    @Test
    void aPositionAboveTheBuildHeightComesBackInsideTheWorld() {
        Location grave = GraveLocationResolver.resolve(player, at(23, world.getMaxHeight() + 40, 23));

        assertNotNull(grave);
        assertTrue(grave.getBlockY() <= world.getMaxHeight() - 1);
    }

    // ------------------------------------------------------------------

    private void assumeMaterial(Material material) {
        if (material == null) {
            // Running against a Minecraft version without powder snow.
            throw new org.opentest4j.TestAbortedException("POWDER_SNOW not available");
        }
    }

    private void damagedBy(EntityDamageEvent.DamageCause cause) {
        player.setLastDamageCause(new EntityDamageEvent(player, cause, 1.0D));
    }

    private Location at(int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    private void fill(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material);
    }

    private void fillColumn(int x, int fromY, int toY, int z, Material material) {
        for (int y = fromY; y <= toY; y++) {
            world.getBlockAt(x, y, z).setType(material);
        }
    }

    private void clearColumn(int x, int fromY, int toY, int z) {
        fillColumn(x, fromY, toY, z, Material.AIR);
    }

    private ChestData chestAt(Location location) {
        List<ItemStack> inventory = new ArrayList<>();
        inventory.add(new ItemStack(Material.DIAMOND, 1));

        return new ChestData(
                inventory,
                location,
                "Steve",
                player.getUniqueId(),
                new Date(),
                false,
                false,
                location.clone().add(0, 1, 0),
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                world.getName(),
                0
        );
    }
}
