package org.appdevforall.k2go.setup.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-434: the dashboard-rebuild state -> view rule (slice 5). Pure, so a plain JVM test.
 *  from(rebuiltOk, rebuildFailed, rebuildServerUp, rebuildServerFailed, redirectCancelled). */
public class RebuildUiStateTest {

    @Test
    public void buildingIsGated() {
        RebuildUiState rb = RebuildUiState.from(false, false, false, false, false);
        assertEquals(RebuildUiState.Phase.BUILDING, rb.phase);
        assertEquals(RebuildUiState.Controls.GATED, rb.controls);
    }

    @Test
    public void rebuiltWaitingForServerIsServerWaitGated() {
        RebuildUiState rb = RebuildUiState.from(true, false, false, false, false);
        assertEquals(RebuildUiState.Phase.SERVER_WAIT, rb.phase);
        assertEquals(RebuildUiState.Controls.GATED, rb.controls);
    }

    @Test
    public void rebuiltAndServerUpRedirectsUntilCancelled() {
        RebuildUiState go = RebuildUiState.from(true, false, true, false, false);
        assertEquals(RebuildUiState.Phase.DONE, go.phase);
        assertEquals(RebuildUiState.Controls.REDIRECT, go.controls);

        RebuildUiState cancelled = RebuildUiState.from(true, false, true, false, true);
        assertEquals(RebuildUiState.Phase.DONE, cancelled.phase);
        assertEquals(RebuildUiState.Controls.FINISH_SUCCESS, cancelled.controls);
    }

    @Test
    public void rebuildFailureIsErrorRebuildFailure() {
        RebuildUiState rb = RebuildUiState.from(false, true, false, false, false);
        assertEquals(RebuildUiState.Phase.ERROR, rb.phase);
        assertTrue(rb.errorIsRebuildFailure);
        assertEquals(RebuildUiState.Controls.FINISH_FAILURE, rb.controls);
    }

    @Test
    public void servicesNeverCameUpIsErrorNotRebuildFailure() {
        RebuildUiState rb = RebuildUiState.from(true, false, false, true, false);
        assertEquals(RebuildUiState.Phase.ERROR, rb.phase);
        assertFalse(rb.errorIsRebuildFailure);   // rebuild succeeded, the server did not come up
        assertEquals(RebuildUiState.Controls.FINISH_FAILURE, rb.controls);
    }
}
