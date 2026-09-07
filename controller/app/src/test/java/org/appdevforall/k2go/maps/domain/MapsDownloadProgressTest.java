/*
 * ============================================================================
 * Name        : MapsDownloadProgressTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. Unit tests for the maps download progress snapshot.
 * ============================================================================
 */
package org.appdevforall.k2go.maps.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapsDownloadProgressTest {

    @Test
    public void activeCarriesPercentAndSpeed() {
        MapsDownloadProgress p = MapsDownloadProgress.active(37, "3.4 MB");
        assertTrue(p.isActive());
        assertTrue(p.isRunning());
        assertEquals(37, p.percent);
        assertEquals("3.4 MB", p.speed);
        assertEquals(MapsDownloadProgress.Phase.ACTIVE, p.phase);
    }

    @Test
    public void pausedIsRunningWithNoSpeed() {
        MapsDownloadProgress p = MapsDownloadProgress.paused(14);
        assertTrue(p.isPaused());
        assertTrue(p.isRunning());
        assertEquals(14, p.percent);
        assertEquals("", p.speed);
    }

    @Test
    public void reconnectingCarriesTheCounter() {
        MapsDownloadProgress p = MapsDownloadProgress.reconnecting(3, 5);
        assertTrue(p.isReconnecting());
        assertTrue(p.isRunning());
        assertEquals(3, p.reconnectAttempt);
        assertEquals(5, p.reconnectTotal);
        assertEquals(-1, p.percent);                // percent is not known during a reconnect
    }

    @Test
    public void completeIsNotRunning() {
        MapsDownloadProgress p = MapsDownloadProgress.complete();
        assertTrue(p.isComplete());
        assertFalse(p.isRunning());
        assertEquals(100, p.percent);
    }

    @Test
    public void noneIsNotRunning() {
        MapsDownloadProgress p = MapsDownloadProgress.none();
        assertFalse(p.isRunning());
        assertEquals(MapsDownloadProgress.Phase.NONE, p.phase);
        assertEquals(-1, p.percent);
    }

    /** Percent is clamped to 0..100, and a negative percent means "unknown" (-1). */
    @Test
    public void percentIsClamped() {
        assertEquals(100, MapsDownloadProgress.active(150, "1 MB").percent);
        assertEquals(-1, MapsDownloadProgress.active(-5, "1 MB").percent);
    }
}
