package org.appdevforall.k2go.dependency.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** K2GO-430: the per-module dash-node minimum registry. Pure data, so a plain JVM test. */
public class DashNodeRequirementTest {

    @Test
    public void forgejoRequiresOneSixFive() {
        assertArrayEquals(new int[]{1, 6, 5}, DashNodeRequirement.minFor("forgejo"));
        assertEquals("1.6.5", DashNodeRequirement.minLabel("forgejo"));
    }

    @Test
    public void otherModulesHaveNoHardMinimum() {
        assertNull(DashNodeRequirement.minFor("kiwix"));
        assertNull(DashNodeRequirement.minFor("maps"));
        assertNull(DashNodeRequirement.minLabel("kolibri"));
    }

    @Test
    public void nullOrUnknownKeyIsSafe() {
        assertNull(DashNodeRequirement.minFor(null));
        assertNull(DashNodeRequirement.minLabel(null));
        assertNull(DashNodeRequirement.minFor(""));
    }
}
