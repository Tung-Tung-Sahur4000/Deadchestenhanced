package me.crylonz.deadchest.drops;

import me.crylonz.deadchest.utils.ConfigKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares the vanilla item despawn timer of the server with the lifetime the
 * vanilla drop mode announces.
 * <p>
 * {@code item-despawn-rate} in spigot.yml is 6000 ticks, 5 minutes, out of the
 * box, and plenty of servers lower it. A rate below
 * {@code vanilla-drop.despawn-seconds} means the server would take a reserved
 * drop away before the plugin means to, so the two configurations are told to
 * agree instead of the plugin taking the drops off the server timer entirely.
 */
public final class DespawnRateAdvisor {

    private static final String DEFAULT_SECTION = "world-settings.default.item-despawn-rate";
    private static final int VANILLA_DEFAULT_TICKS = 6000;

    private DespawnRateAdvisor() {
    }

    /**
     * @return one line per world whose server despawn rate is shorter than the
     * configured lifetime, empty when the two agree or the mode is off
     */
    public static List<String> findMismatches() {
        final List<String> warnings = new ArrayList<>();
        if (!LockedDropService.isVanillaDropModeEnabled()) {
            return warnings;
        }

        final int despawnSeconds = LockedDropService.getDespawnSeconds();
        if (despawnSeconds <= 0) {
            // Drops are meant to stay for good, so any vanilla rate is shorter.
            return warnings;
        }

        final YamlConfiguration spigotConfig = readSpigotConfig();
        if (spigotConfig == null) {
            return warnings;
        }

        final long neededTicks = despawnSeconds * 20L;
        final int defaultRate = spigotConfig.getInt(DEFAULT_SECTION, VANILLA_DEFAULT_TICKS);

        for (World world : worldsInScope()) {
            final int rate = spigotConfig.getInt(
                    "world-settings." + world.getName() + ".item-despawn-rate", defaultRate);

            if (rate > 0 && rate < neededTicks) {
                warnings.add(describe(world.getName(), rate, despawnSeconds));
            }
        }

        return warnings;
    }

    /**
     * @return the worlds the vanilla drop mode actually covers, so a server is not
     * told about a world where the mode never runs
     */
    private static List<World> worldsInScope() {
        final List<World> worlds = new ArrayList<>();
        try {
            for (World world : Bukkit.getWorlds()) {
                if (LockedDropService.appliesIn(world)) {
                    worlds.add(world);
                }
            }
        } catch (Throwable ignored) {
            // No world list available yet : nothing to report.
        }
        return worlds;
    }

    private static String describe(String worldName, int rate, int despawnSeconds) {
        final int rateSeconds = rate / 20;
        return "World '" + worldName + "': spigot.yml 'item-despawn-rate' is " + rate + " ticks ("
                + rateSeconds + "s) but '" + ConfigKey.VANILLA_DROP_DESPAWN_SECONDS + "' is "
                + despawnSeconds + "s. Raise item-despawn-rate to at least " + (despawnSeconds * 20)
                + " so the server agrees with the reserved drop lifetime"
                + (LockedDropService.isDespawnProtectionEnabled()
                ? ", which DeadChest is currently holding back on its own."
                : ". With '" + ConfigKey.VANILLA_DROP_PROTECT_FROM_DESPAWN
                + "' set to false the drops are removed after " + rateSeconds
                + "s instead of the " + despawnSeconds + "s configured.");
    }

    private static YamlConfiguration readSpigotConfig() {
        try {
            return Bukkit.spigot().getConfig();
        } catch (Throwable ignored) {
            // Not a Spigot derivative, or the accessor is missing : nothing to compare.
            return null;
        }
    }
}
