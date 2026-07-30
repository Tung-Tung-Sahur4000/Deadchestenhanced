package me.crylonz.deadchest.drops;

import org.bukkit.entity.Item;

import java.util.UUID;

/**
 * Fallback used on servers without the persistent data API : locks then only
 * live in memory and are lost on restart.
 */
class NoOpDropTagStorage implements DropTagStorage {

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public void write(Item item, UUID ownerId, String ownerName, long expirationTime) {
        // Nothing to persist.
    }

    @Override
    public UUID readOwner(Item item) {
        return null;
    }

    @Override
    public long readExpiration(Item item) {
        return 0L;
    }
}
