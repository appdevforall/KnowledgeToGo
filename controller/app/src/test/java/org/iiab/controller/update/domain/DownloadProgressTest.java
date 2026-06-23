package org.iiab.controller.update.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the OTA download progress model. */
public class DownloadProgressTest {

    @Test public void percentIsClampedAndRounded() {
        assertEquals(50, new DownloadProgress(DownloadProgress.Status.RUNNING, 50, 100).percent());
        assertEquals(0, new DownloadProgress(DownloadProgress.Status.RUNNING, 0, 100).percent());
        assertEquals(100, new DownloadProgress(DownloadProgress.Status.SUCCESSFUL, 100, 100).percent());
        assertEquals(33, new DownloadProgress(DownloadProgress.Status.RUNNING, 1, 3).percent());
    }

    @Test public void unknownTotalIsIndeterminate() {
        DownloadProgress p = new DownloadProgress(DownloadProgress.Status.RUNNING, 1234, -1);
        assertTrue(p.isIndeterminate());
        assertEquals(-1, p.percent());
    }

    @Test public void terminalStates() {
        assertTrue(new DownloadProgress(DownloadProgress.Status.SUCCESSFUL, 10, 10).isTerminal());
        assertTrue(new DownloadProgress(DownloadProgress.Status.FAILED, 0, 10).isTerminal());
        assertFalse(new DownloadProgress(DownloadProgress.Status.RUNNING, 5, 10).isTerminal());
        assertFalse(DownloadProgress.none().isTerminal());
    }

    @Test public void negativeBytesClampedToZero() {
        assertEquals(0, new DownloadProgress(DownloadProgress.Status.PENDING, -5, 100).downloadedBytes);
    }
}
