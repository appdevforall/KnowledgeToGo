package org.iiab.controller.deploy.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExtractProgressTest {

    @Test public void zeroWhenUnknownOrEmpty() {
        assertEquals(0, ExtractProgress.percent(0, 0));
        assertEquals(0, ExtractProgress.percent(10, 0));
        assertEquals(0, ExtractProgress.percent(0, 100));
        assertEquals(0, ExtractProgress.percent(-5, 100));
    }

    @Test public void midValuesRoundDown() {
        assertEquals(50, ExtractProgress.percent(50, 100));
        assertEquals(33, ExtractProgress.percent(1, 3));   // 33.3 -> 33
        assertEquals(68, ExtractProgress.percent(680, 1000));
    }

    @Test public void cappedAt99UntilComplete() {
        assertEquals(99, ExtractProgress.percent(100, 100));   // exactly done -> still 99 (100 is set on completion)
        assertEquals(99, ExtractProgress.percent(999, 1000));
        assertEquals(99, ExtractProgress.percent(5000, 1000)); // over-count (stray stderr lines) still clamps
    }

    @Test public void monotonicNonDecreasingForGrowingDone() {
        int prev = 0;
        for (long done = 0; done <= 1000; done += 37) {
            int p = ExtractProgress.percent(done, 1000);
            org.junit.Assert.assertTrue("percent must not regress", p >= prev);
            prev = p;
        }
    }
}
