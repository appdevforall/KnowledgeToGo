/*
 * ============================================================================
 * Name        : UpdateViewModelPollTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5000. Pure-JVM tests for the per-poll decision that drives
 *               the OTA dialog. Guards the fix for the "stuck on verifying"
 *               regression: once completion has been handled, a later terminal
 *               poll must not re-emit VERIFYING (which would overwrite READY).
 * ============================================================================
 */
package org.iiab.controller.update.presentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.update.domain.DownloadProgress;
import org.iiab.controller.update.presentation.UpdateViewModel.PollOutcome;
import org.junit.Test;

public class UpdateViewModelPollTest {

    private static DownloadProgress progress(DownloadProgress.Status s) {
        return new DownloadProgress(s, 100, 100);
    }

    @Test public void runningEmitsDownloadingAndKeepsPolling() {
        PollOutcome o = UpdateViewModel.decidePoll(progress(DownloadProgress.Status.RUNNING), false);
        assertEquals(UpdateUiState.Status.DOWNLOADING, o.state.status);
        assertFalse(o.fireTerminal);
        assertTrue(o.keepPolling);
    }

    @Test public void firstSuccessEmitsVerifyingFiresTerminalAndStops() {
        PollOutcome o = UpdateViewModel.decidePoll(progress(DownloadProgress.Status.SUCCESSFUL), false);
        assertEquals(UpdateUiState.Status.VERIFYING, o.state.status);
        assertTrue(o.fireTerminal);
        assertFalse(o.keepPolling);
    }

    @Test public void firstFailedEmitsErrorFiresTerminalAndStops() {
        PollOutcome o = UpdateViewModel.decidePoll(progress(DownloadProgress.Status.FAILED), false);
        assertEquals(UpdateUiState.Status.ERROR, o.state.status);
        assertTrue(o.fireTerminal);
        assertFalse(o.keepPolling);
    }

    /** Regression guard (#1): once handled, a later SUCCESSFUL poll must NOT re-emit VERIFYING. */
    @Test public void successAfterHandledEmitsNothingAndStops() {
        PollOutcome o = UpdateViewModel.decidePoll(progress(DownloadProgress.Status.SUCCESSFUL), true);
        assertNull("must not overwrite a completed READY with VERIFYING", o.state);
        assertFalse(o.fireTerminal);
        assertFalse(o.keepPolling);
    }

    /** Once handled, a later FAILED poll must also be inert (no error re-emit, no polling). */
    @Test public void failedAfterHandledEmitsNothingAndStops() {
        PollOutcome o = UpdateViewModel.decidePoll(progress(DownloadProgress.Status.FAILED), true);
        assertNull(o.state);
        assertFalse(o.fireTerminal);
        assertFalse(o.keepPolling);
    }

    /** Terminal callback must fire only on the first terminal observation. */
    @Test public void terminalFiresOnlyOnFirstObservation() {
        assertTrue(UpdateViewModel.decidePoll(progress(DownloadProgress.Status.SUCCESSFUL), false).fireTerminal);
        assertFalse(UpdateViewModel.decidePoll(progress(DownloadProgress.Status.SUCCESSFUL), true).fireTerminal);
    }
}
