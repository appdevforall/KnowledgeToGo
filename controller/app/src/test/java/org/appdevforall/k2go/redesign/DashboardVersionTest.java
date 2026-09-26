package org.appdevforall.k2go.redesign;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-430: the version compare behind the dash-node dependency gate (installBlocked uses it). Pure,
 *  so a plain JVM test; installed(Context) reads a file and is not covered here. */
public class DashboardVersionTest {

    @Test
    public void nullOrJunkIsBelowAnyMinimum() {
        assertFalse(DashboardVersion.atLeast(null, 1, 3, 7));
        assertFalse(DashboardVersion.atLeast("", 1, 3, 7));
        assertFalse(DashboardVersion.atLeast("not-a-version", 1, 3, 7));
    }

    @Test
    public void exactMinimumMeetsIt() {
        assertTrue(DashboardVersion.atLeast("1.3.7", 1, 3, 7));
    }

    @Test
    public void belowMinimumFails() {
        assertFalse(DashboardVersion.atLeast("1.3.6", 1, 3, 7));
        assertFalse(DashboardVersion.atLeast("1.2.9", 1, 3, 7));
        assertFalse(DashboardVersion.atLeast("0.9.9", 1, 3, 7));
        assertFalse(DashboardVersion.atLeast("1.3", 1, 3, 7));   // missing patch = .0, so 1.3.0 < 1.3.7
    }

    @Test
    public void aboveMinimumPasses() {
        assertTrue(DashboardVersion.atLeast("1.3.8", 1, 3, 7));
        assertTrue(DashboardVersion.atLeast("1.3.10", 1, 3, 7));   // numeric compare, not lexical
        assertTrue(DashboardVersion.atLeast("1.4.0", 1, 3, 7));
        assertTrue(DashboardVersion.atLeast("2.0.0", 1, 3, 7));
    }

    @Test
    public void suffixesAreIgnored() {
        assertTrue(DashboardVersion.atLeast("1.3.7-beta", 1, 3, 7));
        assertTrue(DashboardVersion.atLeast("1.4.0+build5", 1, 3, 7));
    }
}
