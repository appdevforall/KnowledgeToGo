package org.iiab.controller.dashboard.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the rebuild progress rule (K2GO-95, Phase 2). */
public class RebuildProgressTest {

    private static final long BIG = 100_000_000L;   // far past any phase median

    // Realistic append-only log fragments, in run order, mirroring tools/rebuild-dashboard.sh output.
    private static final String GIT =
            "[2026-09-02 11:19:54] rebuild start (branch=main, clone=/opt/iiab-android)\n"
          + "[2026-09-02 11:19:54] git fetch + reset --hard origin/main\n";
    private static final String STAGING = GIT
          + "[2026-09-02 11:19:55] staging build\n"
          + "yarn install v1.22.22\n[1/4] Resolving packages...\n[4/4] Building fresh packages...\n"
          + "Done in 139.43s.\n$ tsc\nDone in 5.57s.\n";
    private static final String SMOKE = STAGING
          + "[2026-09-02 11:22:23] smoke test staged build on :4010\nsmoketest OK\n";
    private static final String PROMOTE = SMOKE
          + "[2026-09-02 11:22:26] staged build passed — promoting\n";
    private static final String RESTART = PROMOTE
          + "[2026-09-02 11:22:28] restart dash-node\n[pdsm:dash-node] started\n";
    // A successful run prints a TRANSIENT "smoketest FAIL:" during the verify retry — it must not be
    // read as a phase change (or, later, an error).
    private static final String VERIFY = RESTART
          + "[2026-09-02 11:22:44] verifying live :4000\n"
          + "smoketest FAIL: version endpoint unreachable\nsmoketest OK\n";
    private static final String FINALIZE = VERIFY
          + "[2026-09-02 11:22:46] live OK — finalizing (source + package.json + nginx)\n";
    private static final String COMPLETE = FINALIZE
          + "[2026-09-02 11:23:05] rebuild complete\n";

    @Test public void emptyOrPartialLogIsIndeterminate() {
        assertEquals(RebuildPhase.NONE, RebuildProgress.phaseOf(null));
        assertEquals(RebuildPhase.NONE, RebuildProgress.phaseOf(""));
        assertEquals(RebuildPhase.NONE, RebuildProgress.phaseOf("some unrelated buffering line\n"));
        assertEquals(0, RebuildProgress.percentFor(RebuildPhase.NONE, BIG));
    }

    @Test public void phaseOfReturnsTheMostAdvancedMarker() {
        assertEquals(RebuildPhase.GIT, RebuildProgress.phaseOf(GIT));
        assertEquals(RebuildPhase.STAGING, RebuildProgress.phaseOf(STAGING));
        assertEquals(RebuildPhase.SMOKE, RebuildProgress.phaseOf(SMOKE));
        assertEquals(RebuildPhase.PROMOTE, RebuildProgress.phaseOf(PROMOTE));
        assertEquals(RebuildPhase.RESTART, RebuildProgress.phaseOf(RESTART));
        assertEquals(RebuildPhase.VERIFY, RebuildProgress.phaseOf(VERIFY));
        assertEquals(RebuildPhase.FINALIZE, RebuildProgress.phaseOf(FINALIZE));
    }

    @Test public void transientSmoketestFailIsNotAPhaseChange() {
        // The VERIFY log carries "smoketest FAIL: ..."; the phase stays VERIFY (no terminal here —
        // done/error is owned by /rebuild/status).
        assertEquals(RebuildPhase.VERIFY, RebuildProgress.phaseOf(VERIFY));
    }

    @Test public void completedRunStaysFinalizeBecauseStatusOwnsDone() {
        // "rebuild complete" is not a phase marker; the final snap to 100 is status=done, not the log.
        assertEquals(RebuildPhase.FINALIZE, RebuildProgress.phaseOf(COMPLETE));
    }

    @Test public void stagingStartsAtItsFloorAndInterpolates() {
        assertEquals(RebuildPhase.STAGING.startPercent(),
                RebuildProgress.percentFor(RebuildPhase.STAGING, 0));
        int atMedian = RebuildProgress.percentFor(RebuildPhase.STAGING, RebuildPhase.STAGING.medianMs);
        assertTrue("about ~86% through the segment at the profiled median, got " + atMedian,
                atMedian >= 55 && atMedian < RebuildPhase.STAGING.endPercent());
    }

    @Test public void fillIsMonotonicWithinAPhase() {
        int a = RebuildProgress.percentFor(RebuildPhase.STAGING, 10_000);
        int b = RebuildProgress.percentFor(RebuildPhase.STAGING, 60_000);
        int c = RebuildProgress.percentFor(RebuildPhase.STAGING, 200_000);
        assertTrue("monotonic: " + a + " <= " + b + " <= " + c, a <= b && b <= c);
    }

    @Test public void negativeElapsedIsTreatedAsZero() {
        assertEquals(RebuildProgress.percentFor(RebuildPhase.STAGING, 0),
                RebuildProgress.percentFor(RebuildPhase.STAGING, -5_000));
    }

    @Test public void noPhaseEverReachesItsEndSoTheBarNeverOvershoots() {
        // Every running phase asymptotes strictly below its end; since ends are cumulative, the next
        // phase's start (>= this end) can only move the bar forward — never backward.
        for (RebuildPhase p : RebuildPhase.values()) {
            if (p == RebuildPhase.NONE) continue;
            int max = RebuildProgress.percentFor(p, BIG);
            assertTrue(p + " must stay below its end (" + max + " < " + p.endPercent() + ")",
                    max < p.endPercent());
        }
    }

    @Test public void stagingMaxSitsBelowSmokeStartAcrossTheTransition() {
        assertTrue(RebuildProgress.percentFor(RebuildPhase.STAGING, BIG)
                < RebuildProgress.percentFor(RebuildPhase.SMOKE, 0));
    }

    @Test public void finalizeApproachesButNeverReaches100() {
        int max = RebuildProgress.percentFor(RebuildPhase.FINALIZE, BIG);
        assertTrue("finalize must stay < 100 (status=done snaps to 100), got " + max, max < 100);
        assertTrue("finalize should climb high, got " + max, max >= 90);
    }
}
