package org.iiab.controller.deploy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExtractProgressTest {

    // ---- ADFA-5119: a name only earns the line if it means something ----------

    /** Seen on a device during a real run: a content-addressed blob inside the rootfs. */
    @Test
    public void aContentHashIsNotShown() {
        assertEquals("", ExtractProgress.fileLabel(
                "installed-rootfs/iiab/var/kiwix/0418c83b80f7f7bfaec2738bfbaa1d4c62196c0781702f6eddc8.body"));
    }

    @Test
    public void ordinaryNamesAreStillShown() {
        assertEquals("CodeOnTheGo-latest.apk",
                ExtractProgress.fileLabel("installed-rootfs/iiab/opt/CodeOnTheGo-latest.apk"));
        assertEquals("libstdc++.so.6", ExtractProgress.fileLabel("usr/lib/libstdc++.so.6"));
        assertEquals("sources.list", ExtractProgress.fileLabel("etc/apt/sources.list"));
    }

    /**
     * The threshold has to clear real words made only of hex letters. "deadbeef" and "facade" are
     * the classic traps, and a version like 2026.224 is digits with a break in it.
     */
    @Test
    public void shortHexLikeWordsAreNotMistakenForDigests() {
        assertEquals("deadbeef.conf", ExtractProgress.fileLabel("etc/deadbeef.conf"));
        assertEquals("facade.png", ExtractProgress.fileLabel("share/facade.png"));
        assertEquals("iiab-oa_2026.224_standard.tar.gz",
                ExtractProgress.fileLabel("downloads/iiab-oa_2026.224_standard.tar.gz"));
    }

    /** Fails open: a name we cannot classify is shown, never hidden on a guess. */
    @Test
    public void anythingUnrecognisedIsStillShown() {
        assertEquals("índice-ñ.txt", ExtractProgress.fileLabel("var/índice-ñ.txt"));
        assertEquals("a", ExtractProgress.fileLabel("tmp/a"));
    }

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

    @Test public void etaSecondsUnknownWhenNoBasis() {
        assertEquals(-1L, ExtractProgress.etaSeconds(0, 1000, 100));   // nothing moved
        assertEquals(-1L, ExtractProgress.etaSeconds(500, 0, 100));    // unknown total
        assertEquals(-1L, ExtractProgress.etaSeconds(500, 1000, 0));   // no honest rate
    }

    @Test public void etaSecondsDividesRemainingByRate() {
        assertEquals(5L, ExtractProgress.etaSeconds(500, 1000, 100));   // 500 left / 100 = 5s
        assertEquals(0L, ExtractProgress.etaSeconds(1000, 1000, 100));  // already there
        assertEquals(0L, ExtractProgress.etaSeconds(1500, 1000, 100));  // overshoot -> 0, never negative
    }

    @Test public void unifiedPercentSplitsTheBar() {
        assertEquals(0, ExtractProgress.unifiedPercent(0, false));    // verify start
        assertEquals(25, ExtractProgress.unifiedPercent(50, false));  // verify half -> 25
        assertEquals(49, ExtractProgress.unifiedPercent(99, false));  // verify near end
        assertEquals(50, ExtractProgress.unifiedPercent(0, true));    // extract start
        assertEquals(75, ExtractProgress.unifiedPercent(50, true));   // extract half -> 75
        assertEquals(99, ExtractProgress.unifiedPercent(100, true));  // capped until completion
    }

    @Test public void unifiedPercentClampsAndStaysMonotone() {
        assertEquals(0, ExtractProgress.unifiedPercent(-10, false));
        assertEquals(99, ExtractProgress.unifiedPercent(999, true));
        int prev = 0;
        for (int p = 0; p <= 100; p += 7) {
            int v = ExtractProgress.unifiedPercent(p, false);
            assertTrue("verify half must not regress", v >= prev);
            prev = v;
        }
        assertTrue("handoff stays monotone",
                ExtractProgress.unifiedPercent(0, true) >= ExtractProgress.unifiedPercent(99, false));
    }
}
