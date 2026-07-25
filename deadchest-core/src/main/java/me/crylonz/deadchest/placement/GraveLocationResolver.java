package me.crylonz.deadchest.placement;

import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.utils.ConfigKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import javax.annotation.Nullable;

import static me.crylonz.deadchest.DeadChestLoader.config;

/**
 * Decides where the grave of a dead entity is placed.
 * <p>
 * The rules are applied as a single chain, never in competition with each other:
 * <ol>
 *     <li><b>Environment stage</b> - the death context picks exactly one rule,
 *     in this order: void, lava, water, powder snow, suffocation, free fall.
 *     Only that rule produces a candidate position, so two options can never
 *     fight over the same death.</li>
 *     <li><b>Placement stage</b> - the candidate is clamped inside the world
 *     border and the build height, then accepted only if the block is free, not
 *     already taken by another grave, and buildable by the owner. Otherwise the
 *     closest valid block is searched around it, downwards first.</li>
 * </ol>
 * When no valid block exists at all, the resolver returns {@code null} and the
 * caller leaves the items to the vanilla drops rather than risking them.
 */
public final class GraveLocationResolver {

    /**
     * Vertical offsets tried around a candidate, downwards first: a grave that
     * cannot stay where the entity died belongs below it, not floating above.
     */
    private static final int[] VERTICAL_PROBE_ORDER = {0, -1, 1, -2, 2, -3, 3};

    private static final int DEFAULT_SEARCH_RADIUS = 6;
    private static final int MAX_COLUMN_SCAN = 384;

    /**
     * Upper bound on the number of build permission probes fired for one death.
     * Each probe is an event the whole server sees, and a grave in the middle of
     * a protected area could otherwise fire thousands of them in a single tick.
     */
    private static final int MAX_BUILD_PROBES = 48;

    private static final ThreadLocal<int[]> PROBE_BUDGET = ThreadLocal.withInitial(() -> new int[]{MAX_BUILD_PROBES});

    private GraveLocationResolver() {
    }

    /**
     * Resolves the block a grave must occupy for this death.
     *
     * @param player        dead player
     * @param deathLocation position where the entity died
     * @return usable block location, or {@code null} when no grave can be placed
     */
    @Nullable
    public static Location resolve(final Player player, final Location deathLocation) {
        if (deathLocation == null || deathLocation.getWorld() == null) {
            return null;
        }

        PROBE_BUDGET.get()[0] = MAX_BUILD_PROBES;

        final Location candidate = environmentCandidate(player, GraveBlocks.blockLocation(deathLocation));
        return findPlaceable(player, candidate == null ? GraveBlocks.blockLocation(deathLocation) : candidate);
    }

    // ------------------------------------------------------------------
    // Stage 1 : one rule per death context
    // ------------------------------------------------------------------

    private static Location environmentCandidate(final Player player, final Location rawDeath) {
        final World world = rawDeath.getWorld();
        final EntityDamageEvent.DamageCause cause = damageCause(player);
        final boolean voidDeath = isVoidDeath(rawDeath, world, cause);

        // Every rule below reads blocks around the death position, so it is first
        // brought back inside the world: a position under the bedrock or above the
        // build limit has no block to look at.
        final Location death = clampToWorld(rawDeath);
        if (death == null) {
            return null;
        }

        if (voidDeath) {
            return voidCandidate(player, death);
        }

        final Block deathBlock = safeBlock(death);

        if (GraveBlocks.isLava(deathBlock) || cause == EntityDamageEvent.DamageCause.LAVA) {
            return lavaCandidate(player, death);
        }

        if (GraveBlocks.isWater(deathBlock) || cause == EntityDamageEvent.DamageCause.DROWNING) {
            return waterCandidate(death);
        }

        if (GraveBlocks.isPowderSnow(deathBlock) || cause == EntityDamageEvent.DamageCause.FREEZE) {
            return powderSnowCandidate(player, death);
        }

        if (cause == EntityDamageEvent.DamageCause.SUFFOCATION || !GraveBlocks.isReplaceable(deathBlock)) {
            return suffocationCandidate(player, death);
        }

        if (enabled(ConfigKey.PLACEMENT_GROUND) && !hasSupport(death)) {
            return groundCandidate(death);
        }

        return death;
    }

    /**
     * The entity fell out of the world: nothing at the death position can be
     * used, so the grave goes to the closest real block, and to plain air when
     * the column is empty.
     */
    private static Location voidCandidate(final Player player, final Location death) {
        if (!enabled(ConfigKey.PLACEMENT_VOID)) {
            return death;
        }

        final World world = death.getWorld();
        final Location surface = highestSupport(world, death.getBlockX(), death.getBlockZ());
        if (surface != null) {
            return surface;
        }

        final Location lastGround = GroundTracker.lastSafeGround(player);
        if (lastGround != null) {
            return lastGround;
        }

        // Nothing solid anywhere in the column: the grave is placed in the air at
        // a height the owner can reach.
        final int fallbackY = Math.min(GraveBlocks.maxHeight(world), Math.max(GraveBlocks.minHeight(world) + 1, 64));
        return GraveBlocks.at(world, death.getBlockX(), fallbackY, death.getBlockZ());
    }

    /**
     * Lava has two rules and they are ordered, never combined: the smart one adds
     * the last standing block as a fallback when the lava surface is covered.
     */
    private static Location lavaCandidate(final Player player, final Location death) {
        final boolean smart = enabled(ConfigKey.PLACEMENT_LAVA_SMART);
        final boolean top = enabled(ConfigKey.PLACEMENT_LAVA_TOP);

        if (!smart && !top) {
            return death;
        }

        final Location aboveLava = topOfColumn(death, true);
        if (aboveLava != null) {
            return aboveLava;
        }

        if (smart) {
            final Location lastGround = GroundTracker.lastSafeGround(player);
            if (lastGround != null) {
                return lastGround;
            }
        }
        return death;
    }

    /**
     * Water is a single choice: floating wins over sinking, and sinking is also
     * what a grave that must reach the ground does.
     */
    private static Location waterCandidate(final Location death) {
        if (enabled(ConfigKey.PLACEMENT_WATER_TOP)) {
            final Location surface = topOfColumn(death, false);
            if (surface != null) {
                return surface;
            }
        }

        if (enabled(ConfigKey.PLACEMENT_WATER_BOTTOM) || enabled(ConfigKey.PLACEMENT_GROUND)) {
            return bottomOfWater(death);
        }

        return death;
    }

    /**
     * Powder snow swallows the entity: the last solid ground is tried first, then
     * the surface of the snow itself.
     */
    private static Location powderSnowCandidate(final Player player, final Location death) {
        if (!enabled(ConfigKey.PLACEMENT_POWDER_SNOW)) {
            return death;
        }

        final Location lastGround = GroundTracker.lastSafeGround(player);
        if (lastGround != null) {
            return lastGround;
        }

        final World world = death.getWorld();
        int y = death.getBlockY();
        final int maxY = GraveBlocks.maxHeight(world);
        while (y <= maxY && GraveBlocks.isPowderSnow(world.getBlockAt(death.getBlockX(), y, death.getBlockZ()))) {
            y++;
        }
        return GraveBlocks.at(world, death.getBlockX(), y, death.getBlockZ());
    }

    /**
     * The entity died inside a block: the last solid ground is tried first, then
     * the first free block above the column, then below it.
     */
    private static Location suffocationCandidate(final Player player, final Location death) {
        if (!enabled(ConfigKey.PLACEMENT_SUFFOCATION)) {
            return death;
        }

        final Location lastGround = GroundTracker.lastSafeGround(player);
        if (lastGround != null) {
            return lastGround;
        }

        final World world = death.getWorld();
        final int x = death.getBlockX();
        final int z = death.getBlockZ();

        for (int y = death.getBlockY(); y <= GraveBlocks.maxHeight(world); y++) {
            if (GraveBlocks.isReplaceable(world.getBlockAt(x, y, z))) {
                return GraveBlocks.at(world, x, y, z);
            }
        }
        for (int y = death.getBlockY(); y >= GraveBlocks.minHeight(world); y--) {
            if (GraveBlocks.isReplaceable(world.getBlockAt(x, y, z))) {
                return GraveBlocks.at(world, x, y, z);
            }
        }
        return death;
    }

    /**
     * The entity died in the air: the grave follows it down to the first solid
     * block instead of floating.
     */
    private static Location groundCandidate(final Location death) {
        final World world = death.getWorld();
        final int x = death.getBlockX();
        final int z = death.getBlockZ();

        for (int y = death.getBlockY() - 1; y >= GraveBlocks.minHeight(world); y--) {
            if (GraveBlocks.isSolidSupport(world.getBlockAt(x, y, z))) {
                return GraveBlocks.at(world, x, y + 1, z);
            }
        }
        return death;
    }

    // ------------------------------------------------------------------
    // Stage 2 : make the candidate actually usable
    // ------------------------------------------------------------------

    /**
     * Accepts the candidate, or looks for the closest block that can hold the
     * grave. Blocks already used by another grave are skipped, which is what
     * keeps two entities dying on the same spot from sharing one position.
     *
     * @param player    grave owner
     * @param candidate position produced by the environment stage
     * @return usable block location, or {@code null} when the area is unusable
     */
    @Nullable
    public static Location findPlaceable(final Player player, final Location candidate) {
        final Location start = clampToWorld(candidate);
        if (start == null) {
            return null;
        }

        final Location preferred = raiseAboveFragileSurface(player, start);
        if (preferred != null) {
            return preferred;
        }

        // The column of the candidate comes first, downwards before upwards: a
        // grave refused by the world border or by a protected block belongs just
        // under the death position, not several blocks away from it.
        for (int verticalOffset : VERTICAL_PROBE_ORDER) {
            if (verticalOffset == 0) {
                continue; // already tested above
            }
            final Location tested = clampToWorld(start.clone().add(0, verticalOffset, 0));
            if (tested != null && tested.getBlockY() != start.getBlockY() && isPlaceable(player, tested)) {
                return tested;
            }
        }

        final int radius = searchRadius();
        for (int distance = 1; distance <= radius; distance++) {
            for (int verticalOffset : VERTICAL_PROBE_ORDER) {
                if (Math.abs(verticalOffset) > distance) {
                    continue;
                }
                final Location found = scanRing(player, start, distance, verticalOffset);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Scans the square ring at the given horizontal distance and vertical offset.
     */
    @Nullable
    private static Location scanRing(final Player player, final Location center, final int distance, final int verticalOffset) {
        final World world = center.getWorld();
        final int y = center.getBlockY() + verticalOffset;

        for (int dx = -distance; dx <= distance; dx++) {
            for (int dz = -distance; dz <= distance; dz++) {
                if (distance > 0 && Math.abs(dx) != distance && Math.abs(dz) != distance) {
                    continue; // inner blocks were already tested by a smaller distance
                }

                final Location tested = clampToWorld(GraveBlocks.at(world, center.getBlockX() + dx, y, center.getBlockZ() + dz));
                if (tested != null && isPlaceable(player, tested)) {
                    return tested;
                }
            }
        }
        return null;
    }

    /**
     * @return the candidate itself when usable, raised by one when it would sit
     * on farmland or a path, or {@code null} when it cannot be used at all
     */
    @Nullable
    private static Location raiseAboveFragileSurface(final Player player, final Location candidate) {
        if (!isPlaceable(player, candidate)) {
            return null;
        }

        final Block below = candidate.getWorld().getBlockAt(
                candidate.getBlockX(), candidate.getBlockY() - 1, candidate.getBlockZ());
        if (!GraveBlocks.isFragileSurface(below)) {
            return candidate;
        }

        final Location raised = clampToWorld(candidate.clone().add(0, 1, 0));
        return raised != null && isPlaceable(player, raised) ? raised : candidate;
    }

    /**
     * @param player grave owner
     * @param location candidate block
     * @return {@code true} when a grave can be created there
     */
    public static boolean isPlaceable(final Player player, final Location location) {
        final World world = location.getWorld();
        if (world == null) {
            return false;
        }
        if (location.getBlockY() < GraveBlocks.minHeight(world) || location.getBlockY() > GraveBlocks.maxHeight(world)) {
            return false;
        }
        if (!isInsideWorldBorder(location)) {
            return false;
        }
        if (!GraveBlocks.isReplaceable(world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ()))) {
            return false;
        }
        // Two graves never share a block: the storage is indexed by position, and
        // the second one would hide the first.
        if (DeadChestLoader.getChestData(location) != null) {
            return false;
        }
        if (!enabled(ConfigKey.PLACEMENT_SAFE_LOCATION)) {
            return true;
        }

        final int[] budget = PROBE_BUDGET.get();
        if (budget[0] <= 0) {
            // Budget spent: the remaining checks already proved the block is free,
            // and firing more events would cost more than the guarantee is worth.
            return true;
        }
        budget[0]--;

        return BuildAccessProbe.canBuild(player, world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    /**
     * Pulls a position back inside the world border and the build height.
     *
     * @param location candidate position
     * @return clamped position, or {@code null} when the world is missing
     */
    @Nullable
    private static Location clampToWorld(final Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        final World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        y = Math.max(GraveBlocks.minHeight(world), Math.min(GraveBlocks.maxHeight(world), y));

        final double[] border = borderBounds(world);
        if (border != null) {
            x = (int) Math.max(border[0], Math.min(border[2], x));
            z = (int) Math.max(border[1], Math.min(border[3], z));
        }

        return GraveBlocks.at(world, x, y, z);
    }

    private static boolean isInsideWorldBorder(final Location location) {
        final double[] border = borderBounds(location.getWorld());
        if (border == null) {
            return true;
        }
        return location.getBlockX() >= border[0] && location.getBlockX() <= border[2]
                && location.getBlockZ() >= border[1] && location.getBlockZ() <= border[3];
    }

    /**
     * @return {minX, minZ, maxX, maxZ} of the world border, or {@code null} when
     * the server does not expose one
     */
    @Nullable
    private static double[] borderBounds(final World world) {
        try {
            final WorldBorder worldBorder = world.getWorldBorder();
            if (worldBorder == null) {
                return null;
            }
            final Location center = worldBorder.getCenter();
            final double half = (worldBorder.getSize() / 2.0D) - 1.0D;
            if (half <= 0) {
                return null;
            }
            return new double[]{
                    center.getX() - half,
                    center.getZ() - half,
                    center.getX() + half,
                    center.getZ() + half
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Column helpers
    // ------------------------------------------------------------------

    /**
     * Walks up a liquid column and returns the first free block above it.
     *
     * @param death death position
     * @param lava  {@code true} for lava, {@code false} for water
     * @return free block above the liquid, or {@code null} when it is covered
     */
    @Nullable
    private static Location topOfColumn(final Location death, final boolean lava) {
        final World world = death.getWorld();
        final int x = death.getBlockX();
        final int z = death.getBlockZ();
        final int maxY = GraveBlocks.maxHeight(world);

        int y = death.getBlockY();
        int scanned = 0;
        while (y <= maxY && scanned++ < MAX_COLUMN_SCAN) {
            final Block block = world.getBlockAt(x, y, z);
            final boolean stillInLiquid = lava ? GraveBlocks.isLava(block) : GraveBlocks.isWater(block);
            if (!stillInLiquid) {
                return GraveBlocks.isReplaceable(block) ? GraveBlocks.at(world, x, y, z) : null;
            }
            y++;
        }
        return null;
    }

    /**
     * Walks down a water column and returns the block resting on its floor.
     */
    private static Location bottomOfWater(final Location death) {
        final World world = death.getWorld();
        final int x = death.getBlockX();
        final int z = death.getBlockZ();
        final int minY = GraveBlocks.minHeight(world);

        int y = death.getBlockY();
        while (y > minY && GraveBlocks.isWater(world.getBlockAt(x, y - 1, z))) {
            y--;
        }
        return GraveBlocks.at(world, x, y, z);
    }

    @Nullable
    private static Location highestSupport(final World world, final int x, final int z) {
        int y = world.getHighestBlockYAt(x, z);
        final int minY = GraveBlocks.minHeight(world);
        int scanned = 0;

        while (y >= minY && scanned++ < MAX_COLUMN_SCAN) {
            if (GraveBlocks.isSolidSupport(world.getBlockAt(x, y, z))) {
                return GraveBlocks.at(world, x, Math.min(GraveBlocks.maxHeight(world), y + 1), z);
            }
            y--;
        }
        return null;
    }

    private static boolean hasSupport(final Location location) {
        final World world = location.getWorld();
        return GraveBlocks.isSolidSupport(world.getBlockAt(
                location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()));
    }

    private static boolean isVoidDeath(final Location death, final World world, final EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.VOID || death.getBlockY() < GraveBlocks.minHeight(world);
    }

    /**
     * Reads the block at a position that may sit outside the world.
     */
    private static Block safeBlock(final Location location) {
        final World world = location.getWorld();
        final int y = location.getBlockY();
        if (y < GraveBlocks.minHeight(world) || y > GraveBlocks.maxHeight(world)) {
            return null;
        }
        return world.getBlockAt(location.getBlockX(), y, location.getBlockZ());
    }

    @Nullable
    private static EntityDamageEvent.DamageCause damageCause(final Player player) {
        if (player == null) {
            return null;
        }
        try {
            final EntityDamageEvent lastDamage = player.getLastDamageCause();
            return lastDamage == null ? null : lastDamage.getCause();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int searchRadius() {
        final int configured = config == null ? DEFAULT_SEARCH_RADIUS : config.getInt(ConfigKey.PLACEMENT_SEARCH_RADIUS);
        if (configured <= 0) {
            return DEFAULT_SEARCH_RADIUS;
        }
        return Math.min(configured, 32);
    }

    private static boolean enabled(final ConfigKey key) {
        return config != null && config.getBoolean(key);
    }
}
