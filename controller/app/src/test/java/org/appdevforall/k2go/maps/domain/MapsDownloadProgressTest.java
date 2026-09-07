/*
 * ============================================================================
 * Name        : MapsDownloadProgressTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. Unit tests for the maps download progress rule.
 * ============================================================================
 */
package org.appdevforall.k2go.maps.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapsDownloadProgressTest {

    @Test
    public void activeYieldsPercentAndEta() {
        // 1.0 GiB of 5.99 GiB at ~2.9 MB/s -- the device sample.
        MapsDownloadProgress p = MapsDownloadProgress.of("active", 1_015_808L, 6_427_331_113L, 3_027_977L);
        assertTrue(p.isActive());
        assertTrue(p.isRunning());
        assertEquals(0, p.percent());               // <1%
        assertEquals((6_427_331_113L - 1_015_808L) / 3_027_977L, p.etaSeconds());
    }

    @Test
    public void pausedHasNoEtaButKeepsBytes() {
        MapsDownloadProgress p = MapsDownloadProgress.of("paused", 902_299_648L, 6_427_331_113L, 0L);
        assertTrue(p.isPaused());
        assertTrue(p.isRunning());
        assertEquals(14, p.percent());
        assertEquals(-1L, p.etaSeconds());          // no rate while paused
    }

    @Test
    public void completeIsTheShutdownCue() {
        MapsDownloadProgress p = MapsDownloadProgress.of("complete", 6_427_331_113L, 6_427_331_113L, 0L);
        assertTrue(p.isComplete());
        assertFalse(p.isRunning());
        assertEquals(100, p.percent());
    }

    /** aria2 is still resolving the metalink: total is 0, so percent is "unknown", not a divide-by-zero. */
    @Test
    public void unknownTotalIsMinusOnePercent() {
        MapsDownloadProgress p = MapsDownloadProgress.of("active", 0L, 0L, 0L);
        assertEquals(-1, p.percent());
        assertEquals(-1L, p.etaSeconds());
    }

    @Test
    public void noneAndUnknownStatusAreNotRunning() {
        assertFalse(MapsDownloadProgress.none().isRunning());
        assertEquals(MapsDownloadProgress.Phase.NONE, MapsDownloadProgress.of("waiting", 1, 2, 0).phase);
    }

    /** Percent never exceeds 100 even if aria2 reports completed slightly over total (rounding). */
    @Test
    public void percentClampsAtHundred() {
        assertEquals(100, MapsDownloadProgress.of("active", 101L, 100L, 5L).percent());
    }
}
