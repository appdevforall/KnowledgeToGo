package org.iiab.controller.deploy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link RoleNames} (ADFA-4629). Pure JVM, no Android deps.
 */
public class RoleNamesTest {

    @Test
    public void mapsCalibrewebVarBaseToCalibreWebRole() {
        assertEquals("calibre-web", RoleNames.roleFor("calibreweb"));
    }

    @Test
    public void mirrorsUpstreamOtherDivergences() {
        assertEquals("iiab-admin", RoleNames.roleFor("iiab_admin"));
        assertEquals("osm-vector-maps", RoleNames.roleFor("osm_vector_maps"));
    }

    @Test
    public void passesThroughModulesWhereRoleEqualsVarBase() {
        for (String same : new String[] {"code", "kiwix", "kolibri", "maps", "matomo", "dashboard"}) {
            assertEquals(same, RoleNames.roleFor(same));
        }
    }

    @Test
    public void nullInNullOut() {
        assertNull(RoleNames.roleFor(null));
    }

    @Test
    public void everyMappedRoleNameIsShellSafe() {
        // The role name is interpolated into a root shell command; it must pass the
        // same well-formedness guard as module names (D2).
        for (String varBase : new String[] {"calibreweb", "iiab_admin", "osm_vector_maps"}) {
            assertTrue(ModuleName.isWellFormed(RoleNames.roleFor(varBase)));
        }
    }
}
