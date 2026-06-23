package org.iiab.controller.update.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.update.domain.DownloadProgress;
import org.junit.Test;

/** Pure-JVM tests for the download-snapshot -> UI-state mapping. */
public class UpdateUiStateTest {

    @Test public void runningMapsToDownloadingWithPercent() {
        UpdateUiState s = UpdateUiState.fromDownload(new DownloadProgress(DownloadProgress.Status.RUNNING, 30, 100));
        assertEquals(UpdateUiState.Status.DOWNLOADING, s.status);
        assertEquals(30, s.percent);
    }

    @Test public void unknownTotalIsIndeterminateDownloading() {
        UpdateUiState s = UpdateUiState.fromDownload(new DownloadProgress(DownloadProgress.Status.RUNNING, 10, -1));
        assertEquals(UpdateUiState.Status.DOWNLOADING, s.status);
        assertTrue(s.indeterminate);
    }

    @Test public void successMapsToVerifying() {
        assertEquals(UpdateUiState.Status.VERIFYING,
                UpdateUiState.fromDownload(new DownloadProgress(DownloadProgress.Status.SUCCESSFUL, 100, 100)).status);
    }

    @Test public void failedMapsToError() {
        assertEquals(UpdateUiState.Status.ERROR,
                UpdateUiState.fromDownload(new DownloadProgress(DownloadProgress.Status.FAILED, 0, 100)).status);
    }
}
