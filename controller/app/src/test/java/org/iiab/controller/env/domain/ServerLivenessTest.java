package org.iiab.controller.env.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link ServerLiveness}.
 *
 * <p>The four-way truth table plus the freshness boundary. The case that matters most is the flap
 * ({@link #servicesDownWithProotUpIsStartingNotUp}): a restarting dash-node behind a live nginx must
 * read {@code STARTING}, never {@code UP} — reading {@code /home} as "up" is exactly what the single
 * {@code servicesAnswering} signal removes (ADFA-5336).
 */
public class ServerLivenessTest {

    private static final long NOW = 100_000L;
    private static final long FRESH = ServerLiveness.DEFAULT_FRESH_MS;

    @Test
    public void neverObservedIsUnknown() {
        // observedAtMs == 0: the poll has not run — not a false DOWN (absorbs the old hasObservation()).
        assertEquals(ServerLiveness.Phase.UNKNOWN,
                ServerLiveness.of(false, false, 0L).phase(NOW, FRESH));
        assertEquals(ServerLiveness.Phase.UNKNOWN,
                ServerLiveness.of(true, true, 0L).phase(NOW, FRESH));
    }

    @Test
    public void servicesAnsweringIsUp() {
        assertEquals(ServerLiveness.Phase.UP,
                ServerLiveness.of(true, true, NOW).phase(NOW, FRESH));
    }

    @Test
    public void servicesDownWithProotUpIsStartingNotUp() {
        // The flap: proot alive, /k2go-api not answering yet. STARTING, never UP.
        assertEquals(ServerLiveness.Phase.STARTING,
                ServerLiveness.of(true, false, NOW).phase(NOW, FRESH));
    }

    @Test
    public void nothingPresentIsDown() {
        assertEquals(ServerLiveness.Phase.DOWN,
                ServerLiveness.of(false, false, NOW).phase(NOW, FRESH));
    }

    @Test
    public void servicesAnsweringWinsOverAbsentProot() {
        // Defensive: /proc missed the proot but /k2go-api answers — the usable signal wins, still UP.
        assertEquals(ServerLiveness.Phase.UP,
                ServerLiveness.of(false, true, NOW).phase(NOW, FRESH));
    }

    @Test
    public void aFreshWindowedSnapshotWithinTheWindowIsTrusted() {
        // Stamped FRESH-1 ms ago: still trustworthy, so the observed facts stand (UP).
        assertEquals(ServerLiveness.Phase.UP,
                ServerLiveness.of(true, true, NOW - (FRESH - 1)).phase(NOW, FRESH));
    }

    @Test
    public void theFreshnessBoundaryStillTrustsAtExactlyTheWindow() {
        // At exactly the window it is still fresh (Freshness.fresh uses <=), so UP holds.
        assertEquals(ServerLiveness.Phase.UP,
                ServerLiveness.of(true, true, NOW - FRESH).phase(NOW, FRESH));
    }

    @Test
    public void aStaleSnapshotAgesBackToUnknown() {
        // One ms past the window: the poll stopped feeding us — UNKNOWN, not a stale true "UP".
        assertEquals(ServerLiveness.Phase.UNKNOWN,
                ServerLiveness.of(true, true, NOW - (FRESH + 1)).phase(NOW, FRESH));
    }

    @Test
    public void defaultWindowMatchesTheExplicitOne() {
        // The convenience phase(now) uses DEFAULT_FRESH_MS.
        ServerLiveness live = ServerLiveness.of(true, false, NOW);
        assertEquals(live.phase(NOW, ServerLiveness.DEFAULT_FRESH_MS), live.phase(NOW));
    }

    // --- ADFA-5343a (D1): service-downtime clock ---------------------------------------------------

    @Test
    public void servicesDownMsIsZeroTheTickTheServicesDrop() {
        // First tick where the proot is up but /k2go-api stops answering: downtime starts at 0.
        ServerLiveness up = ServerLiveness.of(true, true, 1_000L);
        ServerLiveness dropped = ServerLiveness.next(up, true, false, 4_000L, FRESH);
        assertEquals(0L, dropped.servicesDownMs(4_000L, FRESH));
    }

    @Test
    public void servicesDownMsAccumulatesAcrossFreshTicks() {
        // A continuous streak of fresh 3 s ticks accumulates real downtime from the drop, not per-tick.
        ServerLiveness t0 = ServerLiveness.next(null, true, false, 1_000L, FRESH);   // dropped at 1000
        ServerLiveness t1 = ServerLiveness.next(t0, true, false, 4_000L, FRESH);
        ServerLiveness t2 = ServerLiveness.next(t1, true, false, 7_000L, FRESH);
        assertEquals(3_000L, t1.servicesDownMs(4_000L, FRESH));
        assertEquals(6_000L, t2.servicesDownMs(7_000L, FRESH));
    }

    @Test
    public void theClockResetsOnAnObservationGap() {
        // GUARDRAIL: the poll went quiet longer than the freshness window (app backgrounded). The
        // previous snapshot is stale, so the streak breaks and downtime restarts from now — a calendar
        // gap must not read as downtime and re-drive the kill loop when the app returns.
        ServerLiveness before = ServerLiveness.next(null, true, false, 1_000L, FRESH);
        long afterGap = 1_000L + FRESH + 1L;   // one ms past the window since `before`
        ServerLiveness resumed = ServerLiveness.next(before, true, false, afterGap, FRESH);
        assertEquals(0L, resumed.servicesDownMs(afterGap, FRESH));   // reset, not FRESH+1 of "downtime"
    }

    @Test
    public void aStaleSnapshotReportsUnknownDowntime() {
        // Read side of the same guardrail: even a snapshot that WAS timing downtime reports -1 once it
        // is itself stale — never time a kill off a reading the poll has not refreshed.
        ServerLiveness dropped = ServerLiveness.of(true, false, 1_000L);   // down since 1000
        assertEquals(-1L, dropped.servicesDownMs(1_000L + FRESH + 1L, FRESH));
    }

    @Test
    public void servicesAnsweringClearsTheClock() {
        ServerLiveness down = ServerLiveness.next(null, true, false, 1_000L, FRESH);
        ServerLiveness up = ServerLiveness.next(down, true, true, 4_000L, FRESH);
        assertEquals(-1L, up.servicesDownMs(4_000L, FRESH));
    }

    @Test
    public void aGoneProotIsNotATrackedDowntime() {
        // Proot absent → DOWN → LAUNCH is the caller's business; there is no service downtime to time.
        ServerLiveness down = ServerLiveness.next(null, true, false, 1_000L, FRESH);
        ServerLiveness gone = ServerLiveness.next(down, false, false, 4_000L, FRESH);
        assertEquals(-1L, gone.servicesDownMs(4_000L, FRESH));
    }
}
