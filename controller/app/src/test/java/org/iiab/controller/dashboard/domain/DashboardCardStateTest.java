package org.iiab.controller.dashboard.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.dashboard.domain.DashboardCardState.Kind;
import org.junit.Test;

/** Pure-JVM tests for the Dashboard card's update-affordance rule (ADFA-5339). */
public class DashboardCardStateTest {

    @Test public void offlineOverridesEverythingAndOffersNoUpdate() {
        // Even with a cached "update available", offline shows No connection — a rebuild can't fetch.
        DashboardCardState s = DashboardCardState.resolve(
                false, false, false, "1.2.7", null, true, true);
        assertEquals(Kind.OFFLINE, s.kind());
        assertTrue(s.isOffline());
        assertFalse(s.primaryIsUpdate());
        assertFalse("no version arrow while offline", s.showsVersionArrow());
    }

    @Test public void liveUpdateAvailableIsUpdateWithTheArrow() {
        DashboardCardState s = DashboardCardState.resolve(
                true, true, true, "1.2.7", "1.3.0", false, false);
        assertEquals(Kind.UPDATE_AVAILABLE, s.kind());
        assertTrue(s.primaryIsUpdate());
        assertTrue(s.showsVersionArrow());
        assertEquals("1.3.0", s.targetVersion());
        assertEquals("1.2.7", s.installedVersion());
    }

    @Test public void liveUpToDateIsRebuildNoArrow() {
        DashboardCardState s = DashboardCardState.resolve(
                true, true, false, "1.3.0", "1.3.0", false, false);
        assertEquals(Kind.UP_TO_DATE, s.kind());
        assertFalse(s.primaryIsUpdate());
        assertFalse(s.showsVersionArrow());
        assertNull(s.targetVersion());
    }

    @Test public void onlineButCheckFailedFallsBackToCache() {
        DashboardCardState up = DashboardCardState.resolve(
                true, false, false, "1.2.7", null, true, true);
        assertEquals(Kind.UPDATE_AVAILABLE, up.kind());
        // The cache holds only the boolean, so a cached update has no target version to arrow to.
        assertFalse("cached update has no target version", up.showsVersionArrow());
        assertNull(up.targetVersion());

        DashboardCardState latest = DashboardCardState.resolve(
                true, false, false, "1.3.0", null, true, false);
        assertEquals(Kind.UP_TO_DATE, latest.kind());
    }

    @Test public void onlineNoResultNoCacheIsChecking() {
        DashboardCardState s = DashboardCardState.resolve(
                true, false, false, null, null, false, false);
        assertEquals(Kind.CHECKING, s.kind());
        assertFalse(s.primaryIsUpdate());
        assertFalse(s.isOffline());
    }

    @Test public void sameVersionNeverArrows() {
        // A malformed "update available" whose target equals installed must not draw "v1.3.0 → v1.3.0".
        DashboardCardState s = DashboardCardState.resolve(
                true, true, true, "1.3.0", "1.3.0", false, false);
        assertEquals(Kind.UPDATE_AVAILABLE, s.kind());
        assertFalse(s.showsVersionArrow());
    }

    @Test public void missingVersionsDoNotArrow() {
        DashboardCardState s = DashboardCardState.resolve(
                true, true, true, "", "1.3.0", false, false);
        assertTrue(s.primaryIsUpdate());
        assertFalse("no installed version -> no arrow", s.showsVersionArrow());
    }
}
