/*
 * ============================================================================
 * Name        : FreshnessTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5146. Pure JVM tests for the last-progress freshness rule that decides
 *               whether a non-terminal in-memory session still counts as work in flight.
 * ============================================================================
 */
package org.appdevforall.k2go.env;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FreshnessTest {

    private static final long T = Freshness.STALE_MS;

    @Test public void neverStampedIsNotFresh() {
        assertFalse(Freshness.fresh(0L, 1_000_000L, T));
    }

    @Test public void justStampedIsFresh() {
        long now = 5_000_000L;
        assertTrue(Freshness.fresh(now, now, T));
    }

    @Test public void withinWindowIsFresh() {
        long now = 5_000_000L;
        assertTrue(Freshness.fresh(now - (T / 2), now, T));
    }

    @Test public void exactlyAtThresholdIsFresh() {
        long now = 5_000_000L;
        assertTrue(Freshness.fresh(now - T, now, T));   // boundary is inclusive (<=)
    }

    @Test public void justPastThresholdIsStale() {
        long now = 5_000_000L;
        assertFalse(Freshness.fresh(now - (T + 1), now, T));
    }

    @Test public void longDeadSessionIsStale() {
        long now = 5_000_000L;
        assertFalse(Freshness.fresh(now - (10 * T), now, T));
    }
}
