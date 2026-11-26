package org.appdevforall.k2go.redesign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-JVM tests for the update-check error reader (real org.json on the test classpath). */
public class DashboardClientTest {

    @Test public void branchMissingAnswerGivesTheBranch() {
        String body = "{\"installed\":\"1.6.5\",\"available\":\"unknown\",\"updateAvailable\":false,"
                + "\"branch\":\"feature/gone\",\"branchMissing\":true,\"error\":\"branch not on the remote\"}";
        assertEquals("feature/gone", DashboardClient.missingBranch(503, body));
    }

    @Test public void offlineAnswerIsNotBranchMissing() {
        String body = "{\"branch\":\"main\",\"branchMissing\":false,\"error\":\"update check unavailable\"}";
        assertNull(DashboardClient.missingBranch(503, body));
    }

    @Test public void oldBoxAnswerAndOtherErrorsAreNotBranchMissing() {
        assertNull("older dash-node: no field", DashboardClient.missingBranch(503, "{\"error\":\"update check unavailable\"}"));
        assertNull("not a 503", DashboardClient.missingBranch(500, "{\"branch\":\"x\",\"branchMissing\":true}"));
        assertNull("no branch name", DashboardClient.missingBranch(503, "{\"branchMissing\":true}"));
        assertNull("not json", DashboardClient.missingBranch(503, "<html>"));
        assertNull(DashboardClient.missingBranch(503, null));
    }
}
