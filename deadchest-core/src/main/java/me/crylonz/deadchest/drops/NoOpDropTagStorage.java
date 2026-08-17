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
    public void write(Item item, UUID ownerId, String ownerName, long creationTime, long expirationTime) {
        // Nothing to persist.
    }

    @Override
    public UUID readOwner(Item item) {
        return null;
    }

    @Override
    public void writeIntegrity(Item item, long sequence, boolean confirmed) {
        // Nothing to persist.
    }

    @Override
    public long readSequence(Item item) {
        return 0L;
    }

    @Override
    public boolean readConfirmed(Item item) {
        return true;
    }

    @Override
    public long readCreation(Item item) {
        return 0L;
    }

    @Override
    public long readExpiration(Item item) {
        return 0L;
    }
}
