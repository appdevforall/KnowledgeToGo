package org.appdevforall.k2go.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-436 (slice 1): the download retry policy as a pure rule. Plain JVM test. */
public class DownloadRetryPolicyTest {

    @Test
    public void retryableKindsAreTransientStalledUnknown() {
        assertTrue(DownloadRetryPolicy.isRetryable(Aria2Exit.Kind.TRANSIENT));
        assertTrue(DownloadRetryPolicy.isRetryable(Aria2Exit.Kind.STALLED));
        assertTrue(DownloadRetryPolicy.isRetryable(Aria2Exit.Kind.UNKNOWN));
    }

    @Test
    public void permanentAndSuccessAreNotRetryable() {
        assertFalse(DownloadRetryPolicy.isRetryable(Aria2Exit.Kind.PERMANENT));
        assertFalse(DownloadRetryPolicy.isRetryable(Aria2Exit.Kind.SUCCESS));
    }

    @Test
    public void decideRetriesWithBackoffWhileUnderBudget() {
        DownloadRetryPolicy.Decision d0 = DownloadRetryPolicy.decide(0);
        assertEquals(DownloadRetryPolicy.Action.RETRY, d0.action);
        assertEquals(1, d0.attempt);
        assertEquals(3_000L, d0.delayMs);

        DownloadRetryPolicy.Decision d1 = DownloadRetryPolicy.decide(1);
        assertEquals(DownloadRetryPolicy.Action.RETRY, d1.action);
        assertEquals(2, d1.attempt);
        assertEquals(6_000L, d1.delayMs);

        DownloadRetryPolicy.Decision d2 = DownloadRetryPolicy.decide(2);
        assertEquals(DownloadRetryPolicy.Action.RETRY, d2.action);
        assertEquals(3, d2.attempt);
        assertEquals(9_000L, d2.delayMs);
    }

    @Test
    public void decideHoldsOnceTheBudgetIsSpent() {
        DownloadRetryPolicy.Decision d3 = DownloadRetryPolicy.decide(3);
        assertEquals(DownloadRetryPolicy.Action.HOLD, d3.action);
        assertEquals(0, d3.attempt);
        assertEquals(0L, d3.delayMs);

        // Defensive: still holds past the budget, no index out of range.
        DownloadRetryPolicy.Decision d10 = DownloadRetryPolicy.decide(10);
        assertEquals(DownloadRetryPolicy.Action.HOLD, d10.action);
        assertEquals(0, d10.attempt);
        assertEquals(0L, d10.delayMs);

        // Defensive: a negative count does not throw and does not escalate below the first delay.
        DownloadRetryPolicy.Decision dNeg = DownloadRetryPolicy.decide(-1);
        assertEquals(DownloadRetryPolicy.Action.RETRY, dNeg.action);
        assertEquals(1, dNeg.attempt);
        assertEquals(3_000L, dNeg.delayMs);
    }

    @Test
    public void progressForgivesOnlyPastTheFloorPlusThreshold() {
        long floor = 100L << 20;   // 100 MiB
        // No bytes yet: never forgives.
        assertFalse(DownloadRetryPolicy.progressForgivesAttempts(0L, floor));
        // Real bytes, but far below the floor: compares against the floor, not zero.
        assertFalse(DownloadRetryPolicy.progressForgivesAttempts(1L << 20, floor));
        // Just under the 1 MiB bar past the floor.
        assertFalse(DownloadRetryPolicy.progressForgivesAttempts(
                floor + DownloadRetryPolicy.PROGRESS_FORGIVE_BYTES - 1, floor));
        // Exactly the bar.
        assertTrue(DownloadRetryPolicy.progressForgivesAttempts(
                floor + DownloadRetryPolicy.PROGRESS_FORGIVE_BYTES, floor));
        // Well past the bar.
        assertTrue(DownloadRetryPolicy.progressForgivesAttempts(floor + (8L << 20), floor));
    }
}
