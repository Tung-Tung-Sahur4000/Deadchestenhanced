package me.crylonz.deadchest.drops;

import org.bukkit.entity.Item;

import java.util.UUID;

/**
 * Persistent storage of the lock tags carried by a dropped item.
 * <p>
 * Tags are written on the entity itself so a reserved drop survives a chunk
 * unload or a server restart. Servers older than the persistent data API simply
 * fall back to in-memory metadata.
 */
interface DropTagStorage {

    /**
     * @return {@code true} when tags survive a chunk unload and a server restart
     */
    boolean isPersistent();

    void write(Item item, UUID ownerId, String ownerName, long expirationTime);

    UUID readOwner(Item item);

    long readExpiration(Item item);

    static DropTagStorage create() {
        try {
            Class.forName("org.bukkit.persistence.PersistentDataContainer");
            Item.class.getMethod("getPersistentDataContainer");
            return new PersistentDropTagStorage();
        } catch (Throwable ignored) {
            return new NoOpDropTagStorage();
        }
    }
}
