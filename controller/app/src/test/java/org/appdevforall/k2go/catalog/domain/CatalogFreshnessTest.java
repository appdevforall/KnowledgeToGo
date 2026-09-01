package org.appdevforall.k2go.catalog.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CatalogFreshnessTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long TTL = 7 * DAY;

    @Test public void dueWhenNeverChecked() {
        assertTrue(CatalogFreshness.dueForCheck(0, 1000, TTL));
        assertTrue(CatalogFreshness.dueForCheck(-5, 1000, TTL));
    }

    @Test public void notDueWithinTtl() {
        long now = 100 * DAY;
        assertFalse(CatalogFreshness.dueForCheck(now - 3 * DAY, now, TTL));
    }

    @Test public void dueAtOrAfterTtl() {
        long now = 100 * DAY;
        assertTrue(CatalogFreshness.dueForCheck(now - TTL, now, TTL));       // exactly TTL -> due
        assertTrue(CatalogFreshness.dueForCheck(now - 8 * DAY, now, TTL));
    }

    @Test public void backwardsClockIsDue() {
        assertTrue(CatalogFreshness.dueForCheck(100 * DAY, 50 * DAY, TTL));
    }

    @Test public void changedComparesHashes() {
        assertFalse(CatalogFreshness.changed("h1", "h1"));   // same -> no swap
        assertTrue(CatalogFreshness.changed("h1", "h2"));    // differ -> swap
        assertTrue(CatalogFreshness.changed(null, "h2"));    // never pulled -> swap
        assertTrue(CatalogFreshness.changed("", "h2"));
        assertFalse(CatalogFreshness.changed("h1", null));   // unknown remote -> no swap
        assertFalse(CatalogFreshness.changed("h1", ""));
    }
}
