package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EtaSmootherTest {

    @Test public void bucketBoundaries() {
        assertEquals(EtaSmoother.UNKNOWN, EtaSmoother.bucketOf(-1));
        assertEquals(0, EtaSmoother.bucketOf(0));
        assertEquals(0, EtaSmoother.bucketOf(59));
        assertEquals(1, EtaSmoother.bucketOf(60));
        assertEquals(2, EtaSmoother.bucketOf(61));      // ceil to whole minutes
        assertEquals(3, EtaSmoother.bucketOf(180));
    }

    @Test public void firstRealBucketAdoptedImmediately() {
        EtaSmoother s = new EtaSmoother(5000L);
        assertEquals(3, s.smooth(3, 1000L));
    }

    @Test public void unknownKeepsLastShown() {
        EtaSmoother s = new EtaSmoother(5000L);
        s.smooth(2, 0L);
        assertEquals(2, s.smooth(EtaSmoother.UNKNOWN, 1000L));   // rate gap must not blank it
        assertEquals(2, s.smooth(EtaSmoother.UNKNOWN, 9000L));
    }

    @Test public void stableBucketDoesNotArmDwell() {
        EtaSmoother s = new EtaSmoother(5000L);
        s.smooth(2, 0L);
        assertEquals(2, s.smooth(2, 1000L));
        assertEquals(2, s.smooth(2, 2000L));
    }

    @Test public void changeHeldLessThanDwellIsIgnored() {
        EtaSmoother s = new EtaSmoother(5000L);
        s.smooth(1, 0L);                 // shown = 1 (about 1 min)
        assertEquals(1, s.smooth(0, 1000L));   // "almost done" candidate at t=1s
        assertEquals(1, s.smooth(0, 3000L));   // still within dwell -> hold "1 min"
        assertEquals(1, s.smooth(0, 5000L));   // 4s held (< 5s) -> still hold
    }

    @Test public void changeHeldForDwellSwitches() {
        EtaSmoother s = new EtaSmoother(5000L);
        s.smooth(1, 0L);
        assertEquals(1, s.smooth(0, 1000L));   // candidate armed at t=1s
        assertEquals(0, s.smooth(0, 6000L));   // 5s later -> adopt "almost done"
    }

    @Test public void flickerBackBeforeDwellNeverSwitches() {
        EtaSmoother s = new EtaSmoother(5000L);
        s.smooth(1, 0L);
        assertEquals(1, s.smooth(0, 1000L));   // candidate = almost done
        assertEquals(1, s.smooth(1, 2000L));   // back to 1 min (== shown) -> candidate cleared
        assertEquals(1, s.smooth(0, 3000L));   // candidate re-armed at t=3s, not t=1s
        assertEquals(1, s.smooth(0, 7000L));   // only 4s since re-arm -> still 1 min
        assertEquals(0, s.smooth(0, 8000L));   // now 5s since re-arm -> switch
    }

    @Test public void resetForgetsState() {
        EtaSmoother s = new EtaSmoother(5000L);
        s.smooth(2, 0L);
        s.reset();
        assertEquals(7, s.smooth(7, 100L));    // adopts the next first value immediately again
    }
}
