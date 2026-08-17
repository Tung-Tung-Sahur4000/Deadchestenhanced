package me.crylonz.deadchest.utils;

import org.bukkit.World;

import java.util.List;
import java.util.Locale;

/**
 * Reads a list of worlds out of the configuration.
 * <p>
 * An entry is either a world name or a whole dimension, so a renamed world and
 * the extra end worlds a multi world setup creates are covered without listing
 * them one by one. An empty list means "everywhere", which is what an option
 * that never had a scope used to do.
 */
public final class WorldScope {

    private WorldScope() {
    }

    /**
     * @param scope entries read from the configuration, a world name or a dimension
     * @param world world of the death
     * @return {@code true} when the world is inside the scope, and for an empty scope
     */
    public static boolean covers(List<String> scope, World world) {
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        if (world == null) {
            return false;
        }

        for (String entry : scope) {
            if (entry == null) {
                continue;
            }

            final String candidate = entry.trim();
            if (candidate.isEmpty()) {
                continue;
            }

            if (candidate.equalsIgnoreCase(world.getName()) || matchesDimension(candidate, world.getEnvironment())) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param entry       one entry of the list
     * @param environment dimension of the world of the death
     * @return {@code true} when the entry names that whole dimension
     */
    private static boolean matchesDimension(String entry, World.Environment environment) {
        switch (entry.toUpperCase(Locale.ROOT)) {
            case "OVERWORLD":
            case "NORMAL":
                return environment == World.Environment.NORMAL;
            case "NETHER":
            case "THE_NETHER":
                return environment == World.Environment.NETHER;
            case "END":
            case "THE_END":
                return environment == World.Environment.THE_END;
            default:
                return false;
        }
    }
}
