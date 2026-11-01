package sh.pcx.packmerger.distribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-session delivery bookkeeping, free of any Bukkit types so it can be unit tested.
 *
 * <p>Tracks three things:</p>
 * <ul>
 *   <li><b>Awaiting players</b>: players who joined while no pack was published yet.
 *       They are sent the pack as soon as the first one is published, whatever
 *       {@code on-new-pack.action} says.</li>
 *   <li><b>Self-heal resends</b>: when the client reports {@code FAILED_DOWNLOAD} or
 *       {@code DISCARDED} for PackMerger's pack, it is re-sent after a delay, at most
 *       {@code maxAttempts} times per session. {@code INVALID_URL} and
 *       {@code FAILED_RELOAD} are never retried because a resend cannot fix them.</li>
 *   <li><b>Hosting health</b>: when several different players' downloads fail for good
 *       with no successful load in between, failed downloads stop kicking until the next
 *       successful load ({@link #noteHosting}). This state outlives player sessions.</li>
 * </ul>
 *
 * <p>State is in-memory only and is reset when a player joins or quits. The Minecraft
 * client drops server packs on disconnect, so nothing about delivery is worth keeping
 * across sessions.</p>
 *
 * <p>Thread-safe: all collections are concurrent, and each player's events arrive on
 * that player's region thread.</p>
 */
public final class DeliveryTracker {

    /** Mirror of Bukkit's {@code PlayerResourcePackStatusEvent.Status}, matched by name. */
    public enum Status {
        SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, ACCEPTED, DOWNLOADED,
        INVALID_URL, FAILED_RELOAD, DISCARDED, OTHER;

        /** Maps a Bukkit status name; unknown names (future API values) become {@link #OTHER}. */
        public static Status of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException e) {
                return OTHER;
            }
        }
    }

    /** What the distributor should do in response to a status report. */
    public enum Action {
        /** In-progress status (accepted, downloaded) or one we don't act on. */
        NONE,
        /** The pack loaded; the player is settled. */
        LOADED,
        /** The player declined the pack. */
        DECLINED,
        /** Schedule a resend after the configured delay. */
        RESEND,
        /** A resend is already scheduled; ignore this duplicate report. */
        RESEND_ALREADY_PENDING,
        /** Retries are used up for this session. */
        GAVE_UP,
        /** The client could not use the URL; resending won't help. */
        INVALID_URL,
        /** The client downloaded the pack but failed to apply it; resending won't help. */
        FAILED_RELOAD
    }

    /**
     * Required-pack enforcement ({@code distribution.required} +
     * {@code distribution.enforce-required.enabled}): a player who declines, or whose
     * download still fails once the resends are used up, is kicked. {@code INVALID_URL}
     * and {@code FAILED_RELOAD} never kick: they point at a hosting or pack problem that
     * would lock every player out.
     *
     * @return {@code true} if this outcome kicks the player
     */
    public static boolean kicks(Action action, boolean required, boolean enforce) {
        return required && enforce && (action == Action.DECLINED || action == Action.GAVE_UP);
    }

    /**
     * Distinct players whose download must fail for good, with no successful load since
     * startup or the last one in between, before failed downloads stop kicking.
     */
    public static final int HOSTING_FAILURE_PLAYERS = 3;

    /** What {@link #noteHosting} changed about download-failure kicks. */
    public enum HostingChange { NONE, PAUSED, RESUMED }

    /** Players who gave up since startup or the last successful load. */
    private final Set<UUID> failedSinceLoad = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean downloadKicksPaused = new AtomicBoolean();

    /**
     * Hosting check: when {@link #HOSTING_FAILURE_PLAYERS} different players gave up and
     * nobody loaded the pack in between, the URL is most likely unreachable (port closed,
     * not forwarded), and kicking would lock everyone out. Failed downloads then stop
     * kicking until the next successful load. Declining still kicks.
     *
     * @param action the decision {@link #onStatus} returned for {@code player}
     * @return whether this outcome paused or resumed download-failure kicks
     */
    public HostingChange noteHosting(UUID player, Action action) {
        if (action == Action.LOADED) {
            failedSinceLoad.clear();
            return downloadKicksPaused.compareAndSet(true, false) ? HostingChange.RESUMED : HostingChange.NONE;
        }
        if (action == Action.GAVE_UP) {
            failedSinceLoad.add(player);
            if (failedSinceLoad.size() >= HOSTING_FAILURE_PLAYERS && downloadKicksPaused.compareAndSet(false, true)) {
                return HostingChange.PAUSED;
            }
        }
        return HostingChange.NONE;
    }

    /** @return {@code true} while failed downloads don't kick because hosting looks broken */
    public boolean downloadKicksPaused() {
        return downloadKicksPaused.get();
    }

    /** @return how many different players gave up since startup or the last successful load */
    public int failedPlayersSinceLoad() {
        return failedSinceLoad.size();
    }

    /** {@link #kicks}, except that {@link Action#GAVE_UP} does not kick while hosting looks broken. */
    public boolean shouldKick(Action action, boolean required, boolean enforce) {
        return kicks(action, required, enforce) && !(action == Action.GAVE_UP && downloadKicksPaused.get());
    }

    /**
     * @param action      what to do
     * @param attempt     for {@link Action#RESEND}, the 1-based number of this resend;
     *                    for {@link Action#GAVE_UP}, how many resends were made
     * @param maxAttempts the cap in force when the decision was made
     */
    public record Decision(Action action, int attempt, int maxAttempts) {}

    private final Set<UUID> awaiting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Set<UUID> resendPending = ConcurrentHashMap.newKeySet();
    private volatile int maxAttempts;

    /** @param maxAttempts resends allowed per session; 0 disables self-heal */
    public DeliveryTracker(int maxAttempts) {
        setMaxAttempts(maxAttempts);
    }

    /** Updates the per-session resend cap (e.g. after {@code /pm reload}). */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(0, maxAttempts);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** A new session starts: forget everything about the player's previous session. */
    public void onJoin(UUID player) {
        forget(player);
    }

    /** The player left: drop all state. */
    public void onQuit(UUID player) {
        forget(player);
    }

    private void forget(UUID player) {
        awaiting.remove(player);
        attempts.remove(player);
        resendPending.remove(player);
    }

    /** Remembers that the player joined before any pack was published. */
    public void markAwaiting(UUID player) {
        awaiting.add(player);
    }

    public boolean isAwaiting(UUID player) {
        return awaiting.contains(player);
    }

    /** Called when the player is actually sent a pack. */
    public void onSent(UUID player) {
        awaiting.remove(player);
    }

    /** @return the awaiting players (and clears the set), for the first publish to flush */
    public List<UUID> drainAwaiting() {
        List<UUID> drained = new ArrayList<>();
        for (UUID id : awaiting) {
            if (awaiting.remove(id)) drained.add(id);
        }
        return drained;
    }

    /** A scheduled resend fired (or was cancelled); allow the next failure to schedule another. */
    public void resendFinished(UUID player) {
        resendPending.remove(player);
    }

    /** @return resends made for the player this session */
    public int attempts(UUID player) {
        return attempts.getOrDefault(player, 0);
    }

    /**
     * Decides what to do with a status report for <em>PackMerger's</em> pack. Callers
     * must already have filtered out reports for other plugins' packs.
     */
    public Decision onStatus(UUID player, Status status) {
        int max = maxAttempts;
        return switch (status) {
            case SUCCESSFULLY_LOADED -> {
                attempts.remove(player);
                resendPending.remove(player);
                yield new Decision(Action.LOADED, 0, max);
            }
            case DECLINED -> {
                resendPending.remove(player);
                yield new Decision(Action.DECLINED, attempts(player), max);
            }
            case FAILED_DOWNLOAD, DISCARDED -> {
                if (!resendPending.add(player)) {
                    yield new Decision(Action.RESEND_ALREADY_PENDING, attempts(player), max);
                }
                int done = attempts(player);
                if (done >= max) {
                    resendPending.remove(player);
                    yield new Decision(Action.GAVE_UP, done, max);
                }
                attempts.put(player, done + 1);
                yield new Decision(Action.RESEND, done + 1, max);
            }
            case INVALID_URL -> {
                resendPending.remove(player);
                yield new Decision(Action.INVALID_URL, attempts(player), max);
            }
            case FAILED_RELOAD -> {
                resendPending.remove(player);
                yield new Decision(Action.FAILED_RELOAD, attempts(player), max);
            }
            default -> new Decision(Action.NONE, attempts(player), max);
        };
    }
}
