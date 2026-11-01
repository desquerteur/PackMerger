package sh.pcx.packmerger.distribution;

import org.junit.jupiter.api.Test;
import sh.pcx.packmerger.distribution.DeliveryTracker.Action;
import sh.pcx.packmerger.distribution.DeliveryTracker.HostingChange;
import sh.pcx.packmerger.distribution.DeliveryTracker.Status;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hosting problem must not lock everyone out: once downloads failed for good for three
 * different players with no successful load in between, failed downloads stop kicking
 * until a download works again. Declining always kicks.
 */
class HostingSafeguardTest {

    private static Action giveUp(DeliveryTracker t, UUID p) {
        Action a;
        while ((a = t.onStatus(p, Status.FAILED_DOWNLOAD).action()) == Action.RESEND) t.resendFinished(p);
        return a;
    }

    @Test
    void threeDifferentPlayers_pauseDownloadKicks() {
        DeliveryTracker t = new DeliveryTracker(3);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

        assertEquals(HostingChange.NONE, t.noteHosting(a, giveUp(t, a)));
        assertTrue(t.shouldKick(Action.GAVE_UP, true, true), "first failure still kicks");
        assertEquals(HostingChange.NONE, t.noteHosting(b, giveUp(t, b)));
        assertTrue(t.shouldKick(Action.GAVE_UP, true, true));

        assertEquals(HostingChange.PAUSED, t.noteHosting(c, giveUp(t, c)));
        assertTrue(t.downloadKicksPaused());
        assertEquals(3, t.failedPlayersSinceLoad());
        assertFalse(t.shouldKick(Action.GAVE_UP, true, true), "the third player is not kicked");
        assertTrue(t.shouldKick(Action.DECLINED, true, true), "declining still kicks");

        // Further failures don't report PAUSED again (one warning only).
        UUID d = UUID.randomUUID();
        assertEquals(HostingChange.NONE, t.noteHosting(d, giveUp(t, d)));
    }

    @Test
    void samePlayerRejoining_countsOnce() {
        DeliveryTracker t = new DeliveryTracker(1);
        UUID a = UUID.randomUUID();
        for (int session = 0; session < 5; session++) {
            t.onJoin(a);
            t.noteHosting(a, giveUp(t, a));
            t.onQuit(a);
        }
        assertFalse(t.downloadKicksPaused());
        assertEquals(1, t.failedPlayersSinceLoad(), "the count outlives the player's sessions");
        assertTrue(t.shouldKick(Action.GAVE_UP, true, true));
    }

    @Test
    void successfulLoad_resumesEnforcement() {
        DeliveryTracker t = new DeliveryTracker(0);
        for (int i = 0; i < 3; i++) {
            UUID p = UUID.randomUUID();
            t.noteHosting(p, giveUp(t, p));
        }
        assertTrue(t.downloadKicksPaused());

        UUID ok = UUID.randomUUID();
        assertEquals(HostingChange.RESUMED, t.noteHosting(ok, t.onStatus(ok, Status.SUCCESSFULLY_LOADED).action()));
        assertFalse(t.downloadKicksPaused());
        assertEquals(0, t.failedPlayersSinceLoad());
        assertTrue(t.shouldKick(Action.GAVE_UP, true, true));
        assertEquals(HostingChange.NONE, t.noteHosting(ok, Action.LOADED));
    }

    @Test
    void aLoadInBetween_resetsTheCount() {
        DeliveryTracker t = new DeliveryTracker(0);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), ok = UUID.randomUUID(), c = UUID.randomUUID();
        t.noteHosting(a, giveUp(t, a));
        t.noteHosting(b, giveUp(t, b));
        t.noteHosting(ok, Action.LOADED);
        assertEquals(HostingChange.NONE, t.noteHosting(c, giveUp(t, c)));
        assertFalse(t.downloadKicksPaused());
    }

    @Test
    void nothingKicks_whenNotForced() {
        DeliveryTracker t = new DeliveryTracker(0);
        assertFalse(t.shouldKick(Action.DECLINED, false, true));
        assertFalse(t.shouldKick(Action.GAVE_UP, true, false));
        assertFalse(t.shouldKick(Action.INVALID_URL, true, true));
    }
}
