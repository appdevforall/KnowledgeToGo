package org.iiab.controller.redesign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.redesign.DashboardRebuildRunner.PreflightResult;
import org.junit.Test;

/** Pure-JVM tests for the preflight-output parser (ADFA-5011). Uses the real org.json on the test
 *  classpath; no Android deps. */
public class DashboardRebuildRunnerTest {

    @Test public void parsesOkResultWithVersions() {
        String out = "[preflight] fetch OK\n"
                + "PREFLIGHT_RESULT={\"ok\":true,\"installed\":\"1.0.1\",\"available\":\"1.1.0\",\"update_available\":true,\"reasons\":[]}\n";
        PreflightResult r = PreflightResult.parse(out);
        assertTrue(r.ok);
        assertEquals("1.0.1", r.installed);
        assertEquals("1.1.0", r.available);
        assertTrue(r.updateAvailable);
        assertTrue(r.reasons.isEmpty());
    }

    @Test public void parsesFailureWithReasons() {
        String out = "PREFLIGHT_RESULT={\"ok\":false,\"installed\":\"1.0.1\",\"available\":\"unknown\","
                + "\"update_available\":false,\"reasons\":[\"dirty_worktree\",\"fetch_failed\"]}";
        PreflightResult r = PreflightResult.parse(out);
        assertFalse(r.ok);
        assertEquals(2, r.reasons.size());
        assertEquals("dirty_worktree, fetch_failed", r.reasonSummary());
    }

    @Test public void takesLastResultLineWhenRepeated() {
        String out = "PREFLIGHT_RESULT={\"ok\":false,\"reasons\":[\"x\"]}\n"
                + "PREFLIGHT_RESULT={\"ok\":true,\"installed\":\"1.1.0\",\"available\":\"1.1.0\",\"update_available\":false,\"reasons\":[]}\n";
        PreflightResult r = PreflightResult.parse(out);
        assertTrue(r.ok);
        assertEquals("1.1.0", r.installed);
    }

    @Test public void missingLineIsNotOk() {
        PreflightResult r = PreflightResult.parse("some logs\nno verdict here\n");
        assertFalse(r.ok);
        assertEquals("no_preflight_output", r.reasonSummary());
    }

    @Test public void malformedJsonIsNotOk() {
        PreflightResult r = PreflightResult.parse("PREFLIGHT_RESULT={not json}");
        assertFalse(r.ok);
        assertEquals("bad_preflight_json", r.reasonSummary());
    }

    @Test public void nullOutputIsNotOk() {
        assertFalse(PreflightResult.parse(null).ok);
    }
}
