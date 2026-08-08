package org.iiab.controller.system.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SystemFacts} — mostly that the three facts stay independent,
 * which is the entire reason the type exists. Pure JVM.
 */
public class SystemFactsTest {

    @Test
    public void aFreshDeviceHasNoSystemAndNothingIsWrongWithIt() {
        SystemFacts f = SystemFacts.none();
        assertFalse(f.isInstalled());
        assertFalse(f.isServerUp());
        // Healthy, because there is nothing to be damaged. Reporting "unhealthy" here
        // would send a first-run device down the recovery path.
        assertTrue(f.isHealthy());
        assertFalse(f.isUsable());
    }

    @Test
    public void installedIsNotTheSameAsRunning() {
        SystemFacts off = SystemFacts.of(true, true, false);
        assertTrue(off.isInstalled());
        assertTrue(off.isUsable());
        assertFalse(off.isServerUp());
    }

    @Test
    public void installedIsNotTheSameAsHealthy() {
        SystemFacts damaged = SystemFacts.of(true, false, false);
        assertTrue(damaged.isInstalled());
        assertFalse(damaged.isHealthy());
        // Usable is the pair, not either one: this is the state the recovery dialog
        // exists for, and nothing but a repair should run against it.
        assertFalse(damaged.isUsable());
    }

    @Test
    public void aRunningBoxIsInstalledHealthyAndUp() {
        SystemFacts f = SystemFacts.of(true, true, true);
        assertTrue(f.isUsable());
        assertTrue(f.isServerUp());
    }

    @Test
    public void factsWithTheSameValuesAreEqual() {
        assertEquals(SystemFacts.of(true, true, false), SystemFacts.of(true, true, false));
        assertEquals(SystemFacts.of(false, true, false), SystemFacts.none());
        assertNotEquals(SystemFacts.of(true, true, false), SystemFacts.of(true, true, true));
        assertEquals(SystemFacts.of(true, false, true).hashCode(),
                SystemFacts.of(true, false, true).hashCode());
    }
}
