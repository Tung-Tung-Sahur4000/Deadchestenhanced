package me.crylonz.deadchest.integrity;

/**
 * Crash-consistency state of a {@link me.crylonz.deadchest.ChestData} entry.
 * <p>
 * DeadChest and the vanilla server keep player items in two independent stores:
 * the plugin database (written immediately) and the player {@code .dat} file
 * (written by the server on autosave, quit or clean shutdown). When the JVM is
 * killed without a graceful shutdown (OOM killer, {@code kill -9}, host crash),
 * those two stores can disagree: the deadchest survives with the items while the
 * player file rolls back to a state where the death never happened, and the same
 * items exist twice.
 * <p>
 * Every transition that moves items between the two stores is therefore staged:
 * the chest first enters a non-{@link #CONFIRMED} state, and only becomes
 * confirmed once the matching player data has actually been flushed to disk.
 * A state left un-confirmed after a restart is exactly the signature of a crash,
 * and is settled against the player file when its owner reconnects.
 */
public enum ChestIntegrityState {

    /**
     * The chest exists but the death that created it is not known to be
     * persisted on the player side yet. If the player file rolled back, the
     * player still owns the items and the chest is a duplicate.
     */
    PENDING,

    /**
     * The chest and the player data agree: the death (or the previous state
     * transition) is durable on both sides. This is the only state in which a
     * chest may be opened, expire or drop its content.
     */
    CONFIRMED,

    /**
     * The content has been handed over to a player, but the receiving player
     * data is not known to be persisted yet. If the player file rolled back,
     * the hand over never happened and the chest must be restored.
     */
    CLAIMED;

    /**
     * Parses a state stored in the database.
     *
     * @param value raw stored value, may be {@code null}
     * @return matching state, or {@link #CONFIRMED} when unreadable
     */
    public static ChestIntegrityState fromStorage(String value) {
        if (value == null) {
            return CONFIRMED;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return CONFIRMED;
        }
    }

    /**
     * Indicates whether a chest in this state is safe to interact with.
     *
     * @return {@code true} when the chest is fully reconciled
     */
    public boolean isSettled() {
        return this == CONFIRMED;
    }
}
