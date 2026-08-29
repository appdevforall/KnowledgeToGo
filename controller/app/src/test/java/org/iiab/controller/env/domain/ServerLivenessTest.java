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
}
