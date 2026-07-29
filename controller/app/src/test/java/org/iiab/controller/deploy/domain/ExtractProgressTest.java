package org.iiab.controller.deploy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExtractProgressTest {

    @Test public void zeroWhenUnknownOrEmpty() {
        assertEquals(0, ExtractProgress.percent(0, 0));
        assertEquals(0, ExtractProgress.percent(10, 0));
        assertEquals(0, ExtractProgress.percent(0, 100));
        assertEquals(0, ExtractProgress.percent(-5, 100));
    }

    @Test public void midValuesRoundDown() {
        assertEquals(50, ExtractProgress.percent(50, 100));
        assertEquals(33, ExtractProgress.percent(1, 3));
        assertEquals(68, ExtractProgress.percent(680, 1000));
    }

    @Test public void cappedAt99UntilComplete() {
        assertEquals(99, ExtractProgress.percent(100, 100));
        assertEquals(99, ExtractProgress.percent(999, 1000));
        assertEquals(99, ExtractProgress.percent(5000, 1000));
    }

    @Test public void monotonicNonDecreasingForGrowingDone() {
        int prev = 0;
        for (long done = 0; done <= 1000; done += 37) {
            int p = ExtractProgress.percent(done, 1000);
            assertTrue("percent must not regress", p >= prev);
            prev = p;
        }
    }

    @Test public void firstLineTakesTextBeforeNewline() {
        assertEquals("Extracting System...", ExtractProgress.firstLine("Extracting System...\n(This takes a while)"));
        assertEquals("Solo una linea", ExtractProgress.firstLine("Solo una linea"));
        assertEquals("", ExtractProgress.firstLine(null));
        assertEquals("trimmed", ExtractProgress.firstLine("  trimmed  \nrest"));
    }

    @Test public void fileLabelIsBasenameFilesOnly() {
        assertEquals("os.py", ExtractProgress.fileLabel("usr/lib/python3.11/os.py"));
        assertEquals("foo", ExtractProgress.fileLabel("foo"));
        assertEquals("", ExtractProgress.fileLabel("usr/lib/"));
        assertEquals("", ExtractProgress.fileLabel(""));
        assertEquals("", ExtractProgress.fileLabel(null));
    }
}
