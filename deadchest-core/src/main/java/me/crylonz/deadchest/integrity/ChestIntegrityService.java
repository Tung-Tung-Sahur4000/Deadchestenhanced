package me.crylonz.deadchest.integrity;

import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.DeadChestManager;
import me.crylonz.deadchest.db.ChestDataRepository;
import me.crylonz.deadchest.drops.LockedDropService;
import me.crylonz.deadchest.utils.ConfigKey;
import org.bukkit.entity.Player;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static me.crylonz.deadchest.DeadChestLoader.config;
import static me.crylonz.deadchest.utils.Utils.generateLog;

/**
 * Keeps the plugin database and the vanilla player files consistent across a
 * crash.
 * <p>
 * The server writes {@code playerdata/&lt;uuid&gt;.dat} on autosave, on quit and on a
 * clean shutdown. DeadChest writes its own database as soon as a player dies.
 * Between those two moments the two stores disagree, and a process that is
 * killed without shutting down (OOM killer, {@code kill -9}, host failure)
 * freezes that disagreement:
 * <ul>
 *     <li>the deadchest is on disk with the items;</li>
 *     <li>the player file rolls back to before the death, with the same items
 *     still in the inventory.</li>
 * </ul>
 * The player then reconnects, is told by the vanilla server that no death ever
 * happened, and finds a deadchest holding a second copy of everything.
 * <p>
 * Every transfer between the two stores is therefore staged. The chest records
 * which player must carry which sequence number for the transfer to be real, the
 * same sequence is stamped on the player (inside the player file, next to the
 * inventory), and the transfer is only settled once that player data has been
 * flushed. After a crash the stamp and the chest disagree exactly when the
 * player file rolled back, which is what {@link #reconcile(Player)} detects on
 * the next login.
 */
public final class ChestIntegrityService {

    /**
     * Highest sequence handed out per player during this server session. Keeps
     * the numbering monotonic even when the chest that carried the previous
     * sequence has already been removed.
     */
    private static final Map<UUID, Long> sessionSequences = new ConcurrentHashMap<>();

    private ChestIntegrityService() {
    }

    /**
     * @return {@code true} when the crash duplication protection is active
     */
    public static boolean isEnabled() {
        return config == null || config.getBoolean(ConfigKey.INTEGRITY_PROTECTION_ENABLED);
    }

    /**
     * @return {@code true} when player data must be flushed to disk as soon as
     * items move between a player and a deadchest
     */
    public static boolean shouldFlushPlayerData() {
        return config == null || config.getBoolean(ConfigKey.INTEGRITY_FLUSH_PLAYER_DATA);
    }

    /**
     * @return {@code true} when a chest proven to be a rollback duplicate must
     * be destroyed instead of being handed to the player a second time
     */
    public static boolean shouldVoidRollbackDuplicates() {
        return config == null || !"keep".equalsIgnoreCase(config.getString(ConfigKey.INTEGRITY_ON_ROLLBACK));
    }

    /**
     * Stages the deadchest created by a death.
     * <p>
     * Must be called before the player inventory is cleared, so the stamp is
     * part of whatever the server writes next for that player.
     *
     * @param player player who died
     * @param chest  chest holding the death inventory
     */
    public static void beginDeath(@Nonnull final Player player, @Nonnull final ChestData chest) {
        if (!isEnabled()) {
            chest.setIntegrityState(ChestIntegrityState.CONFIRMED);
            return;
        }

        final long sequence = allocateSequence(player);
        if (sequence == 0L) {
            // The player carries no stamp, so no login will ever be able to prove
            // that this death was saved. Staging the chest anyway would have it
            // destroyed as a false duplicate: better keep the historic behavior.
            chest.setIntegrityState(ChestIntegrityState.CONFIRMED);
            warn("Could not stamp " + player.getName() + " : deadchest created without crash duplication protection.");
            return;
        }

        chest.setIntegrityOwner(player.getUniqueId());
        chest.setIntegritySequence(sequence);
        chest.setIntegrityState(ChestIntegrityState.PENDING);
    }

    /**
     * Stages the hand over of a chest content to a player.
     * <p>
     * Must be called before the items are given, so a crash in the middle of the
     * hand over is detected instead of silently deleting or duplicating the
     * content.
     *
     * @param player player receiving the items
     * @param chest  chest being emptied
     */
    public static void beginClaim(@Nonnull final Player player, @Nonnull final ChestData chest) {
        chest.setIntegrityOwner(player.getUniqueId());
        chest.setIntegritySequence(isEnabled() ? allocateSequence(player) : 0L);
        chest.setIntegrityState(ChestIntegrityState.CLAIMED);
    }

    /**
     * Stages a hand over into a player inventory and waits for the staged state
     * to reach the disk.
     * <p>
     * Once this returned {@code true}, a crash can no longer make the items
     * exist twice nor vanish: the row is marked as claimed, and the next login
     * of the receiver decides whether the hand over happened or must be undone.
     *
     * @param player player receiving the items
     * @param chest  chest being emptied
     * @return {@code true} when the items may be handed over
     */
    public static boolean stageClaim(@Nonnull final Player player, @Nonnull final ChestData chest) {
        final ChestIntegrityState previousState = chest.getIntegrityState();
        final UUID previousOwner = chest.getIntegrityOwner();
        final long previousSequence = chest.getIntegritySequence();

        beginClaim(player, chest);

        if (ChestDataRepository.saveIntegrityDurable(chest)) {
            return true;
        }

        chest.setIntegrityState(previousState);
        chest.setIntegrityOwner(previousOwner);
        chest.setIntegritySequence(previousSequence);
        warn("Deadchest [" + chest.getPlayerName() + "] at " + describe(chest)
                + " could not be marked as claimed, hand over refused to avoid duplicating its content.");
        return false;
    }

    /**
     * Deletes a chest before its content is dropped in the world.
     * <p>
     * Items on the ground live in the chunk, not in the player file, so there is
     * no stamp to compare later: the row is removed first, which trades a
     * possible loss of the just dropped items for the guarantee that they can
     * never come back as duplicates.
     *
     * @param chest chest about to be emptied on the ground
     * @return {@code true} when the items may be dropped
     */
    public static boolean releaseToWorld(@Nonnull final ChestData chest) {
        if (ChestDataRepository.removeDurable(chest)) {
            return true;
        }

        warn("Deadchest [" + chest.getPlayerName() + "] at " + describe(chest)
                + " could not be deleted, drop refused to avoid duplicating its content.");
        return false;
    }

    /**
     * Flushes the player data and settles every transfer that was waiting on it.
     * <p>
     * Called right after a respawn, right after a claim and on quit: those are
     * the moments where the player side of a transfer can be made durable.
     *
     * @param player player whose data must be persisted
     * @return number of chests settled
     */
    public static int flushAndSettle(final Player player) {
        if (player == null) {
            return 0;
        }

        // Without the forced save there is nothing to wait for: the transfer is
        // completed right away, which is the historic behavior.
        final boolean playerDataDurable = !isEnabled()
                || !shouldFlushPlayerData()
                || PlayerDataStamp.flush(player);

        // The vanilla drop mode stages its drops on the same stamp, so it settles
        // on the same flush.
        return settle(player, playerDataDurable) + LockedDropService.settleIntegrity(player);
    }

    /**
     * Settles the staged transfers of a player.
     * <p>
     * The stamp is deliberately kept on the confirmed chests: a crash can still
     * roll the player file back below that sequence later on, and the next login
     * has to be able to notice it.
     *
     * @param player            player owning the transfers
     * @param playerDataDurable whether the player side of the transfers can be
     *                          considered written to disk
     * @return number of chests settled
     */
    private static int settle(final Player player, final boolean playerDataDurable) {
        int settled = 0;
        for (ChestData chest : stampedChestsOf(player.getUniqueId())) {
            if (chest.getIntegrityState() == ChestIntegrityState.PENDING) {
                confirm(chest);
                settled++;
            } else if (chest.getIntegrityState() == ChestIntegrityState.CLAIMED && playerDataDurable) {
                // The items are on the player side for good, the row can go.
                completeClaim(chest);
                settled++;
            }
        }
        return settled;
    }

    /**
     * Settles the transfers of a reconnecting player against what their player
     * file actually kept.
     * <p>
     * A stamp missing from the player data means that everything the plugin did
     * for that player after the last successful save was rolled back by the
     * crash. Depending on the direction of the transfer, this means either that
     * the player still owns the items a chest holds (the chest is a duplicate)
     * or that the player never received the items a chest handed over (the chest
     * must come back).
     *
     * @param player reconnecting player
     */
    public static void reconcile(final Player player) {
        if (player == null || !isEnabled()) {
            return;
        }

        final long persistedSequence = PlayerDataStamp.readSequence(player);

        // Reserved vanilla drops are judged against the same stamp, and they exist
        // even when this player has no chest at all.
        LockedDropService.reconcileIntegrity(player, persistedSequence);

        final List<ChestData> stamped = stampedChestsOf(player.getUniqueId());
        if (stamped.isEmpty()) {
            return;
        }

        for (ChestData chest : stamped) {
            final boolean playerSidePersisted = chest.getIntegritySequence() <= persistedSequence;

            if (playerSidePersisted) {
                if (chest.getIntegrityState() == ChestIntegrityState.CLAIMED) {
                    completeClaim(chest);
                } else if (chest.getIntegrityState() == ChestIntegrityState.PENDING) {
                    confirm(chest);
                }
                continue;
            }

            if (chest.getIntegrityState() == ChestIntegrityState.CLAIMED) {
                restoreRolledBackClaim(chest, player);
            } else {
                voidRolledBackDeath(chest, player);
            }
        }
    }

    /**
     * Marks a chest as reconciled and persists the new state.
     *
     * @param chest chest to confirm
     */
    public static void confirm(@Nonnull final ChestData chest) {
        chest.setIntegrityState(ChestIntegrityState.CONFIRMED);
        ChestDataRepository.saveIntegrityAsync(chest);
    }

    /**
     * Finishes a hand over whose receiving player data is now on disk: the chest
     * has no reason to exist any more.
     *
     * @param chest emptied chest
     */
    private static void completeClaim(@Nonnull final ChestData chest) {
        DeadChestLoader.getSchedulerAdapter().executeAtLocation(
                chest.getChestLocation(),
                () -> DeadChestLoader.getChestDataCache().removeChestData(chest));
    }

    /**
     * The death that created this chest was rolled back by the crash: the player
     * reconnected with the items still in the inventory, so the chest content is
     * a duplicate of what the player already owns.
     *
     * @param chest  duplicated chest
     * @param player owner of the rolled back death
     */
    private static void voidRolledBackDeath(@Nonnull final ChestData chest, final Player player) {
        final String description = "[" + chest.getPlayerName() + "] at " + describe(chest);

        if (!shouldVoidRollbackDuplicates()) {
            chest.setIntegrityState(ChestIntegrityState.CONFIRMED);
            ChestDataRepository.saveIntegrityAsync(chest);
            warn("Deadchest " + description + " was created by a death the server never saved (server crash). "
                    + "Its content is a duplicate of the items " + chest.getPlayerName() + " still owns, "
                    + "but '" + ConfigKey.INTEGRITY_ON_ROLLBACK + "' is set to keep : chest kept.");
            return;
        }

        warn("Deadchest " + description + " removed : the death was never saved on the player side (server crash), "
                + "its content is already back in the inventory of " + chest.getPlayerName() + ".");

        DeadChestLoader.getSchedulerAdapter().executeAtLocation(
                chest.getChestLocation(),
                () -> DeadChestManager.removeDeadChest(chest));

        if (player != null && DeadChestLoader.local != null) {
            player.sendMessage(DeadChestLoader.local.prefixed("chest.rollback-voided"));
        }
    }

    /**
     * The hand over of this chest was rolled back by the crash: the player never
     * kept the items, so the chest becomes usable again.
     *
     * @param chest  chest to restore
     * @param player player whose claim was lost
     */
    private static void restoreRolledBackClaim(@Nonnull final ChestData chest, final Player player) {
        chest.setIntegrityState(ChestIntegrityState.CONFIRMED);
        // The lost claim is history: keeping its stamp would make every future
        // login of that player report the same rollback again.
        chest.setIntegrityOwner(null);
        chest.setIntegritySequence(0L);
        chest.abortTransfer();
        ChestDataRepository.saveIntegrityAsync(chest);

        warn("Deadchest [" + chest.getPlayerName() + "] at " + describe(chest)
                + " restored : the items were handed to " + (player == null ? "a player" : player.getName())
                + " but the server crashed before that inventory was saved.");
    }

    /**
     * Allocates the next sequence for a player and stamps it on the player data.
     *
     * Shared by the deadchests and by the reserved vanilla drops : both stamp the
     * same player data, so the numbering has to come from one place.
     *
     * @param player player to stamp
     * @return allocated sequence, or {@code 0} when the player could not be
     * stamped, meaning no proof will be available later
     */
    public static long allocateSequence(@Nonnull final Player player) {
        final UUID uuid = player.getUniqueId();

        long highest = Math.max(PlayerDataStamp.readSequence(player), sessionSequences.getOrDefault(uuid, 0L));
        for (ChestData chest : stampedChestsOf(uuid)) {
            highest = Math.max(highest, chest.getIntegritySequence());
        }
        // Reserved vanilla drops are stamped on the same player data.
        highest = Math.max(highest, LockedDropService.highestStampedSequence(uuid));

        final long allocated = highest + 1;
        if (!PlayerDataStamp.writeSequence(player, allocated)) {
            return 0L;
        }

        sessionSequences.put(uuid, allocated);
        return allocated;
    }

    /**
     * @param uuid player to look up
     * @return chests whose last transfer is stamped on this player data
     */
    private static List<ChestData> stampedChestsOf(final UUID uuid) {
        final List<ChestData> stamped = new ArrayList<>();
        if (uuid == null) {
            return stamped;
        }

        for (ChestData chest : DeadChestLoader.getChestDataCache().getAllChestData().values()) {
            if (chest != null && uuid.equals(chest.getIntegrityOwner())) {
                stamped.add(chest);
            }
        }
        return stamped;
    }

    /**
     * @return number of chests still waiting for a reconciliation
     */
    public static int countUnsettled() {
        int count = 0;
        for (ChestData chest : DeadChestLoader.getChestDataCache().getAllChestData().values()) {
            if (chest != null && !chest.isSettled()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Clears the per-session sequence cache. Test and reload helper.
     */
    public static void resetSessionState() {
        sessionSequences.clear();
    }

    private static String describe(final ChestData chest) {
        return chest.getWorldName() + " X:" + chest.getChestLocation().getBlockX()
                + " Y:" + chest.getChestLocation().getBlockY()
                + " Z:" + chest.getChestLocation().getBlockZ();
    }

    private static void warn(final String message) {
        DeadChestLoader.log.warning("[DeadChest] " + message);
        generateLog(message);
    }
}
