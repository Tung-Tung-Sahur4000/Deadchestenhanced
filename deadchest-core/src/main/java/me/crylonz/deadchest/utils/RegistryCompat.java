package me.crylonz.deadchest.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

import java.util.Locale;

/**
 * Version safe lookups for the Bukkit types that moved from enum constants to
 * registries : materials that only exist from a given Minecraft version, and
 * enchantments which are resolved by key since 1.13.
 * <p>
 * Nothing here references such a constant directly, so a name unknown to the
 * running server resolves to {@code null} instead of throwing
 * {@code NoSuchFieldError} on a newer Minecraft version.
 * <p>
 * Particles and sounds are resolved the same way in
 * {@link me.crylonz.deadchest.listener.ClickListener}, which owns the configured
 * fallback lists for the pickup effects.
 */
public final class RegistryCompat {

    private static final String VANISHING_CURSE_KEY = "vanishing_curse";
    private static final String VANISHING_CURSE_LEGACY_NAME = "VANISHING_CURSE";

    private RegistryCompat() {
    }

    /**
     * @param name material name, namespaced or not
     * @return matching material or {@code null} when this version does not have it
     */
    public static Material material(String name) {
        if (name == null) {
            return null;
        }

        String normalized = name.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        try {
            return Material.getMaterial(normalized.toUpperCase(Locale.ROOT)
                    .replace('.', '_')
                    .replace('-', '_')
                    .replace(' ', '_'));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Detects the Curse of Vanishing without depending on a constant, as the
     * enchantment constants moved to a registry.
     *
     * @param enchantment enchantment to test
     * @return {@code true} when this is the Curse of Vanishing
     */
    public static boolean isVanishingCurse(Enchantment enchantment) {
        if (enchantment == null) {
            return false;
        }

        try {
            NamespacedKey key = enchantment.getKey();
            if (key != null && VANISHING_CURSE_KEY.equals(key.getKey())) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall back to the legacy name below.
        }

        try {
            return VANISHING_CURSE_LEGACY_NAME.equalsIgnoreCase(enchantment.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
