package me.crylonz.deadchest.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistryCompatTest {

    @Test
    void resolvesMaterialsByName() {
        assertEquals(Material.CHEST, RegistryCompat.material("CHEST"));
        assertEquals(Material.ENDER_CHEST, RegistryCompat.material("ender-chest"));
        assertEquals(Material.BARREL, RegistryCompat.material("minecraft:barrel"));
    }

    @Test
    void unknownMaterialResolvesToNullInsteadOfFailing() {
        assertNull(RegistryCompat.material("NOT_A_MATERIAL"));
        assertNull(RegistryCompat.material(null));
        assertNull(RegistryCompat.material("   "));
    }

    @Test
    void detectsTheVanishingCurseByKey() {
        Enchantment curse = mock(Enchantment.class);
        when(curse.getKey()).thenReturn(NamespacedKey.minecraft("vanishing_curse"));

        assertTrue(RegistryCompat.isVanishingCurse(curse));
    }

    @Test
    void otherEnchantmentsAreNotTheVanishingCurse() {
        Enchantment mending = mock(Enchantment.class);
        when(mending.getKey()).thenReturn(NamespacedKey.minecraft("mending"));

        assertFalse(RegistryCompat.isVanishingCurse(mending));
        assertFalse(RegistryCompat.isVanishingCurse(null));
    }
}
