/*
 * ============================================================================
 * Name        : MapsRunroleCommandTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4900. Unit tests for the maps runrole command builder — the per-layer
 *               selection -> local_vars mapping, the "off" encoding, the search engine, the
 *               allowlist fallback, and that it forces --reinstall.
 * ============================================================================
 */
package org.iiab.controller.install.domain;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapsRunroleCommandTest {

    @Test
    public void writesSelectedLayersAndForcesReinstall() {
        String cmd = MapsRunroleCommand.build("14", "13", "10", true);
        assertTrue(cmd.contains("maps_vector_zoom: 14"));
        assertTrue(cmd.contains("maps_satellite_zoom: 13"));
        assertTrue(cmd.contains("maps_terrain_zoom: 10"));
        assertTrue(cmd.contains("maps_search_engine: \"static\""));
        assertTrue(cmd.contains("maps_region_downloader: True"));
        assertTrue(cmd.contains("./runrole --reinstall maps"));
    }

    @Test
    public void offLayersMapToNoneAndSearchEngineEmpty() {
        String cmd = MapsRunroleCommand.build("nat-z8", null, "0-none", false);
        assertTrue(cmd.contains("maps_vector_zoom: nat-z8"));
        assertTrue(cmd.contains("maps_satellite_zoom: none"));
        assertTrue(cmd.contains("maps_terrain_zoom: 0-none"));
        assertTrue(cmd.contains("maps_search_engine: \"\""));
    }

    @Test
    public void invalidValuesFallBackToSafeDefaults() {
        String cmd = MapsRunroleCommand.build("bogus", "99", "42", true);
        assertTrue(cmd.contains("maps_vector_zoom: 11"));
        assertTrue(cmd.contains("maps_satellite_zoom: none"));
        assertTrue(cmd.contains("maps_terrain_zoom: 0-none"));
    }

    @Test
    public void alwaysWritesEveryVarTheRoleReferences() {
        String cmd = MapsRunroleCommand.build("11", "9", "7", true);
        for (String var : new String[]{
                "maps_install: True", "maps_enabled: True", "maps_region_downloader: True",
                "maps_vector_zoom:", "maps_satellite_zoom:", "maps_terrain_zoom:",
                "maps_search_engine:", "maps_search_static_db: pop-1k-cities",
                "maps_search_nominatim_db: basic", "maps_ne6_zoom: 6",
                "maps_preset_full_quality_regions: []"}) {
            assertTrue("missing " + var, cmd.contains(var));
        }
    }
}
