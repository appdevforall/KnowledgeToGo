/*
 * ============================================================================
 * Name        : ModuleDownloadProgressTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394, K2GO-593. Unit tests for the module download progress snapshot.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModuleDownloadProgressTest {

    @Test
    public void activeCarriesPercentSpeedAndTimeLeft() {
        ModuleDownloadProgress p = ModuleDownloadProgress.active("forgejo", 37, "3.4 MB", 120);
        assertTrue(p.isActive());
        assertTrue(p.isRunning());
        assertEquals(37, p.percent);
        assertEquals("3.4 MB", p.speed);
        assertEquals(120, p.etaSeconds);
        assertEquals(ModuleDownloadProgress.Phase.ACTIVE, p.phase);
        assertNull(p.holdReason);
    }

    @Test
    public void pausedIsRunningWithNoSpeed() {
        ModuleDownloadProgress p = ModuleDownloadProgress.paused("maps", 14);
        assertTrue(p.isPaused());
        assertTrue(p.isRunning());
        assertEquals(14, p.percent);
        assertEquals("", p.speed);
        assertEquals(-1, p.etaSeconds);
    }

    @Test
    public void heldKeepsThePercentAndTheReason() {
        ModuleDownloadProgress p = ModuleDownloadProgress.held("forgejo", 52, DownloadHold.Reason.METERED_NO_CONSENT);
        assertTrue(p.isHeld());
        assertTrue(p.isRunning());
        assertFalse(p.isPaused());
        assertEquals(52, p.percent);
        assertEquals(DownloadHold.Reason.METERED_NO_CONSENT, p.holdReason);
    }

    @Test
    public void reconnectingCarriesTheCounter() {
        ModuleDownloadProgress p = ModuleDownloadProgress.reconnecting("maps", 3, 5);
        assertTrue(p.isReconnecting());
        assertTrue(p.isRunning());
        assertEquals(3, p.reconnectAttempt);
        assertEquals(5, p.reconnectTotal);
        assertEquals(-1, p.percent);                // percent is not known during a reconnect
    }

    @Test
    public void completeIsNotRunning() {
        ModuleDownloadProgress p = ModuleDownloadProgress.complete("forgejo");
        assertTrue(p.isComplete());
        assertFalse(p.isRunning());
        assertEquals(100, p.percent);
    }

    @Test
    public void noneIsNotRunningAndBelongsToNoModule() {
        ModuleDownloadProgress p = ModuleDownloadProgress.none();
        assertFalse(p.isRunning());
        assertEquals(ModuleDownloadProgress.Phase.NONE, p.phase);
        assertEquals(-1, p.percent);
        assertFalse(p.isFor("maps"));
        assertFalse(p.isFor(null));
    }

    @Test
    public void isForMatchesOnlyItsModule() {
        ModuleDownloadProgress p = ModuleDownloadProgress.active("forgejo", 1, "", -1);
        assertTrue(p.isFor("forgejo"));
        assertFalse(p.isFor("maps"));
    }

    /** Percent is clamped to 0..100, a negative percent or time left means "unknown" (-1). */
    @Test
    public void valuesAreClamped() {
        assertEquals(100, ModuleDownloadProgress.active("maps", 150, "1 MB", -1).percent);
        assertEquals(-1, ModuleDownloadProgress.active("maps", -5, "1 MB", -1).percent);
        assertEquals(-1, ModuleDownloadProgress.active("maps", 5, "1 MB", -9).etaSeconds);
    }
}
