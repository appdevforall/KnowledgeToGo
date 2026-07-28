/*
 * ============================================================================
 * Name        : MapsRunroleCommand.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4900. Pure builder for the maps runrole command driven by the wizard's
 *               per-layer selection. Kept out of InstallService so it is unit-testable (no Android
 *               deps). Translates the selection into the maps role's local_vars
 *               (roles/maps/tasks/install_frontend.yml): satellite/terrain "none" turns the layer
 *               off; search maps to maps_search_engine + maps_search_static_db. Every var the role's
 *               iiab.ini step references is written so the play never hits an undefined var. Values
 *               are validated against a fixed allowlist (D2 shell-injection guard); anything
 *               unexpected falls back to a safe default. --reinstall forces install.yml to re-fetch
 *               the chosen tiles (a plain runrole skips it because maps ships in the base image).
 * ============================================================================
 */
package org.iiab.controller.install.domain;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class MapsRunroleCommand {
    private MapsRunroleCommand() {}

    private static final Set<String> VECTOR_OK = new HashSet<>(Arrays.asList("nat-z8", "osm-z11", "osm-z14"));
    private static final Set<String> SAT_OK = new HashSet<>(Arrays.asList("none", "7", "9", "11", "13"));
    private static final Set<String> TERRAIN_OK = new HashSet<>(Arrays.asList("none", "7", "8", "9", "10"));
    private static final String LV = "/etc/iiab/local_vars.yml";

    /** Build the sed-delete + echo (append-if-missing) + runrole command for the given selection. */
    public static String build(String vector, String sat, String terrain, boolean searchOn) {
        String vq = VECTOR_OK.contains(vector) ? vector : "osm-z11";
        String s = SAT_OK.contains(sat) ? sat : "none";
        String t = TERRAIN_OK.contains(terrain) ? terrain : "none";
        String engine = searchOn ? "static" : "";
        return "sed -i -E '/^[[:space:]]*maps_(install|enabled|region_downloader|vector_quality|" +
                "satellite_zoom|terrain_zoom|search_engine|search_static_db|search_nominatim_db|" +
                "ne6_zoom|preset_full_quality_regions)[[:space:]]*:/d' " + LV +
                " && echo 'maps_install: True' >> " + LV +
                " && echo 'maps_enabled: True' >> " + LV +
                " && echo 'maps_region_downloader: True' >> " + LV +
                " && echo 'maps_vector_quality: " + vq + "' >> " + LV +
                " && echo 'maps_satellite_zoom: " + s + "' >> " + LV +
                " && echo 'maps_terrain_zoom: " + t + "' >> " + LV +
                " && echo 'maps_search_engine: \"" + engine + "\"' >> " + LV +
                " && echo 'maps_search_static_db: pop-1k-cities' >> " + LV +
                " && echo 'maps_search_nominatim_db: basic' >> " + LV +
                " && echo 'maps_ne6_zoom: full' >> " + LV +
                " && echo 'maps_preset_full_quality_regions: []' >> " + LV +
                " && cd /opt/iiab/iiab && ./runrole --reinstall maps";
    }
}
