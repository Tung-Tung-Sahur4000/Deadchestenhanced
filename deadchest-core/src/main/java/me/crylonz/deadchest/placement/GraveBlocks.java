package me.crylonz.deadchest.placement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Block level helpers shared by the grave placement rules.
 * <p>
 * Everything here stays on names rather than enum constants so the plugin keeps
 * working on the Minecraft versions where a material does not exist yet.
 */
public final class GraveBlocks {

    /**
     * Blocks a grave may overwrite: they are either nothing at all, a liquid, or
     * vegetation the game itself replaces when a block is placed.
     */
    private static final Set<String> REPLACEABLE = new HashSet<>(Arrays.asList(
            "AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA", "BUBBLE_COLUMN",
            "GRASS", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT",
            "SNOW", "POWDER_SNOW", "FIRE", "SOUL_FIRE", "VINE", "GLOW_LICHEN",
            "LIGHT", "STRUCTURE_VOID", "COBWEB"
    ));

    /**
     * Surfaces that turn to dirt or misbehave under a block, the grave is raised
     * by one when it would land on them.
     */
    private static final Set<String> FRAGILE_SURFACE = new HashSet<>(Arrays.asList(
            "FARMLAND", "DIRT_PATH", "GRASS_PATH"
    ));

    private GraveBlocks() {
    }

    /**
     * @param block block to test
     * @return {@code true} when a grave can take that block position
     */
    public static boolean isReplaceable(final Block block) {
        if (block == null) {
            return false;
        }
        return REPLACEABLE.contains(block.getType().name());
    }

    /**
     * @param block block to test
     * @return {@code true} when the block can hold a grave on top of it
     */
    public static boolean isSolidSupport(final Block block) {
        if (block == null) {
            return false;
        }
        final Material type = block.getType();
        return type.isSolid() && !isLava(block) && !isWater(block);
    }

    public static boolean isWater(final Block block) {
        return block != null && "WATER".equals(block.getType().name());
    }

    public static boolean isLava(final Block block) {
        return block != null && "LAVA".equals(block.getType().name());
    }

    public static boolean isPowderSnow(final Block block) {
        return block != null && "POWDER_SNOW".equals(block.getType().name());
    }

    public static boolean isFragileSurface(final Block block) {
        return block != null && FRAGILE_SURFACE.contains(block.getType().name());
    }

    /**
     * @return lowest Y a block can occupy in the world
     */
    public static int minHeight(final World world) {
        try {
            return world.getMinHeight();
        } catch (Throwable ignored) {
            // Pre 1.17 servers have no negative build height.
            return 0;
        }
    }

    /**
     * @return highest Y a block can occupy in the world
     */
    public static int maxHeight(final World world) {
        return world.getMaxHeight() - 1;
    }

    /**
     * Builds a block centered location, which is what the rest of the plugin
     * stores and compares.
     *
     * @param world world holding the block
     * @param x     block x
     * @param y     block y
     * @param z     block z
     * @return block location
     */
    public static Location at(final World world, final int x, final int y, final int z) {
        return new Location(world, x, y, z);
    }

    /**
     * @param location source location
     * @return the same position as a clean block location
     */
    public static Location blockLocation(final Location location) {
        return at(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
