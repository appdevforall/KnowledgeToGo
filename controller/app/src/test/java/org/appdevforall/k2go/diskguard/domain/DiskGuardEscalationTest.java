package org.appdevforall.k2go.diskguard.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.diskguard.domain.DiskGuardEscalation.Action;
import org.appdevforall.k2go.diskguard.domain.DiskGuardEscalation.Verdict;

import org.junit.Test;

public class DiskGuardEscalationTest {

    private static final long WINDOW = 30L * 60L * 1000L;
    private static final int ESCALATE = 3;

    @Test
    public void notCritical_isNoneAndResetsCount() {
        Verdict v = DiskGuardEscalation.next(false, 1000L, 500L, 2, WINDOW, ESCALATE);
        assertEquals(Action.NONE, v.action);
        assertEquals(0, v.tripCount);
    }

    @Test
    public void firstCritical_isContainTripOne() {
        Verdict v = DiskGuardEscalation.next(true, 1000L, -1L, 0, WINDOW, ESCALATE);
        assertEquals(Action.CONTAIN, v.action);
        assertEquals(1, v.tripCount);
        assertTrue(v.firstOfSpell);
    }

    @Test
    public void consecutiveCritical_incrementsWithinWindow() {
        Verdict v = DiskGuardEscalation.next(true, 2000L, 1000L, 1, WINDOW, ESCALATE);
        assertEquals(Action.CONTAIN, v.action);
        assertEquals(2, v.tripCount);
        assertFalse(v.firstOfSpell);
    }

    @Test
    public void escalatesAtThreshold() {
        Verdict v = DiskGuardEscalation.next(true, 3000L, 2000L, 2, WINDOW, ESCALATE);
        assertEquals(Action.ESCALATE, v.action);
        assertEquals(3, v.tripCount);
    }

    @Test
    public void gapBeyondWindow_startsNewSpell() {
        // prevCount is 2, but the gap exceeds the window, so the spell resets to trip 1.
        Verdict v = DiskGuardEscalation.next(true, 100_000_000L, 1000L, 2, WINDOW, ESCALATE);
        assertEquals(Action.CONTAIN, v.action);
        assertEquals(1, v.tripCount);
        assertTrue(v.firstOfSpell);
    }
}
