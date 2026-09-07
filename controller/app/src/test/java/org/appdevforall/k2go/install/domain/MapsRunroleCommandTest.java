/*
 * ============================================================================
 * Name        : MapsRunroleCommandTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4900. Unit tests for the maps runrole command builder — the per-layer
 *               selection -> local_vars mapping, the "off" encoding, the search engine, the
 *               allowlist fallback, and (K2GO-393) that it selects the runrole mode at runtime from
 *               the completion marker.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapsRunroleCommandTest {

    @Test
    public void writesSelectedLayers() {
        String cmd = MapsRunroleCommand.build("14", "13", "10", true);
        assertTrue(cmd.contains("maps_vector_zoom: 14"));
        assertTrue(cmd.contains("maps_satellite_zoom: 13"));
        assertTrue(cmd.contains("maps_terrain_zoom: 10"));
        assertTrue(cmd.contains("maps_search_engine: \"static\""));
        assertTrue(cmd.contains("maps_region_downloader: True"));
    }

    /** K2GO-393: the mode is chosen at runtime by the completion marker, not hardcoded to --reinstall.
     *  Marker present -> --reinstall (deletes it, re-runs); marker absent -> plain runrole (recovers a
     *  half-done install; a bare --reinstall would error there). Mirrors runrole's own state gate. */
    @Test
    public void selectsRunroleModeAtRuntimeFromTheMarker() {
        String cmd = MapsRunroleCommand.build("11", "9", "7", true);
        // the runtime gate on iiab_state.yml, and BOTH branches present
        assertTrue(cmd.contains("grep -q '^maps_' /etc/iiab/iiab_state.yml"));
        assertTrue(cmd.contains("./runrole --reinstall maps"));   // marker present
        assertTrue(cmd.contains("./runrole maps"));               // marker absent (recovery)
    }

    /** K2GO-394: a safe hex secret + a valid port write the RPC handshake, and the sed purges the
     *  old download_rpc_* lines so a re-run does not stack stale ones. */
    @Test
    public void writesRpcHandshakeWhenSecretIsSafe() {
        String cmd = MapsRunroleCommand.build("11", "9", "7", true, "deadbeefcafe", 6810);
        assertTrue(cmd.contains("maps_download_rpc_secret: deadbeefcafe"));
        assertTrue(cmd.contains("maps_download_rpc_port: 6810"));
        assertTrue(cmd.contains("download_rpc_secret|download_rpc_port"));   // purged by the sed
    }

    /** K2GO-394 (D2): an unsafe secret or a bad port drops the handshake entirely -- the download still
     *  runs, just without RPC control. Never interpolate an unvalidated token into the shell command. */
    @Test
    public void dropsRpcHandshakeForUnsafeSecretOrPort() {
        String injified = MapsRunroleCommand.build("11", "9", "7", true, "x; rm -rf /", 6810);
        assertFalse(injified.contains("maps_download_rpc_secret"));
        assertFalse(injified.contains("rm -rf"));
        String badPort = MapsRunroleCommand.build("11", "9", "7", true, "deadbeefcafe", 22);
        assertFalse(badPort.contains("maps_download_rpc_secret"));
    }

    /** The 4-arg build stays RPC-free (the recovery/A1 path and these tests rely on it). */
    @Test
    public void fourArgBuildHasNoRpcHandshake() {
        assertFalse(MapsRunroleCommand.build("11", "9", "7", true).contains("maps_download_rpc"));
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
