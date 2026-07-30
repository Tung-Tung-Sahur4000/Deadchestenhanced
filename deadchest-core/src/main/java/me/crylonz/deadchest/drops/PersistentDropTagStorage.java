package me.crylonz.deadchest.drops;

import me.crylonz.deadchest.DeadChestLoader;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Stores the lock tags inside the entity persistent data container, available
 * since Minecraft 1.14.
 */
class PersistentDropTagStorage implements DropTagStorage {

    private static final String OWNER_KEY = "locked-drop-owner";
    private static final String OWNER_NAME_KEY = "locked-drop-owner-name";
    private static final String CREATION_KEY = "locked-drop-created";
    private static final String EXPIRATION_KEY = "locked-drop-expiration";

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void write(Item item, UUID ownerId, String ownerName, long creationTime, long expirationTime) {
        PersistentDataContainer container = container(item);
        NamespacedKey ownerKey = key(OWNER_KEY);
        if (container == null || ownerKey == null || ownerId == null) {
            return;
        }

        try {
            container.set(ownerKey, PersistentDataType.STRING, ownerId.toString());
            container.set(key(CREATION_KEY), PersistentDataType.LONG, creationTime);
            container.set(key(EXPIRATION_KEY), PersistentDataType.LONG, expirationTime);
            if (ownerName != null) {
                container.set(key(OWNER_NAME_KEY), PersistentDataType.STRING, ownerName);
            }
        } catch (Throwable ignored) {
            // Container not writable on this platform : in-memory metadata still applies.
        }
    }

    @Override
    public UUID readOwner(Item item) {
        String owner = readString(item, OWNER_KEY);
        if (owner == null) {
            return null;
        }

        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public long readCreation(Item item) {
        return readLong(item, CREATION_KEY);
    }

    @Override
    public long readExpiration(Item item) {
        return readLong(item, EXPIRATION_KEY);
    }

    private long readLong(Item item, String rawKey) {
        PersistentDataContainer container = container(item);
        NamespacedKey namespacedKey = key(rawKey);
        if (container == null || namespacedKey == null) {
            return 0L;
        }

        try {
            Long value = container.get(namespacedKey, PersistentDataType.LONG);
            return value == null ? 0L : value;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private String readString(Item item, String rawKey) {
        PersistentDataContainer container = container(item);
        NamespacedKey namespacedKey = key(rawKey);
        if (container == null || namespacedKey == null) {
            return null;
        }

        try {
            return container.get(namespacedKey, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private PersistentDataContainer container(Item item) {
        if (item == null) {
            return null;
        }

        try {
            return item.getPersistentDataContainer();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private NamespacedKey key(String value) {
        Plugin plugin = DeadChestLoader.plugin;
        if (plugin == null) {
            return null;
        }

        try {
            return new NamespacedKey(plugin, value);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
