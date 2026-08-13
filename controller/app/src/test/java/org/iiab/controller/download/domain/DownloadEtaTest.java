package org.iiab.controller.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ByteToken} and {@link DownloadEta} — turning aria2's progress line back
 * into numbers, and into an estimate we can explain. Pure JVM.
 *
 * <p>The distinction under most of these: **no estimate** and **a bad estimate** are different
 * answers. A stalled transfer has no honest completion time, and a very large number would be read
 * downstream as a very slow transfer — a different situation with a different remedy.
 */
public class DownloadEtaTest {

    private static final long KI = 1024L, MI = KI * 1024L, GI = MI * 1024L;

    // ---- ByteToken: the units aria2 actually prints -------------------------

    @Test
    public void readsTheTokensFromARealProgressLine() {
        // [#2089b0 400MiB/1.0GiB(39%) CN:4 DL:4.5MiB ETA:2m20s]
        assertEquals(400 * MI, ByteToken.parse("400MiB"));
        assertEquals(GI, ByteToken.parse("1.0GiB"));
        assertEquals(Math.round(4.5 * MI), ByteToken.parse("4.5MiB"));
    }

    @Test
    public void aMissingUnitMeansBytes() {
        assertEquals(850L, ByteToken.parse("850"));
        assertEquals(850L, ByteToken.parse("850B"));
    }

    /**
     * The one that costs real money if it is wrong. At gigabyte scale, reading GiB as GB is a 7%
     * error, and it lands straight in an estimate the user is asked to act on.
     */
    @Test
    public void binaryAndDecimalUnitsAreNotConflated() {
        assertEquals(GI, ByteToken.parse("1GiB"));
        assertEquals(1_000_000_000L, ByteToken.parse("1GB"));
        assertTrue(ByteToken.parse("1GiB") > ByteToken.parse("1GB"));
        assertEquals(KI, ByteToken.parse("1KiB"));
        assertEquals(1000L, ByteToken.parse("1KB"));
    }

    @Test
    public void aTrailingPerSecondIsStripped() {
        assertEquals(Math.round(4.5 * MI), ByteToken.parse("4.5MiB/s"));
    }

    @Test
    public void caseAndSurroundingSpaceDoNotMatter() {
        assertEquals(MI, ByteToken.parse("  1mib  "));
        assertEquals(MI, ByteToken.parse("1MIB"));
    }

    /** An unreadable token is UNKNOWN, never zero — zero is a legitimate rate and means something. */
    @Test
    public void unreadableIsUnknownAndNotZero() {
        for (String s : new String[]{null, "", "   ", "--", "MiB", "abc", "1.2XB", "-5MiB"}) {
            assertEquals(String.valueOf(s), ByteToken.UNKNOWN, ByteToken.parse(s));
        }
        assertEquals(0L, ByteToken.parse("0"));
        assertEquals(0L, ByteToken.parse("0B"));
    }

    // ---- DownloadEta: the estimate ------------------------------------------

    @Test
    public void theOrdinaryCase() {
        // 600 MiB left at 4 MiB/s
        long left = 400 * MI, total = GI, rate = 4 * MI;
        assertEquals((total - left) / rate, DownloadEta.secondsRemaining(left, total, rate));
    }

    @Test
    public void alreadyCompleteIsZeroSecondsAndNotNegative() {
        assertEquals(0L, DownloadEta.secondsRemaining(GI, GI, MI));
        assertEquals(0L, DownloadEta.secondsRemaining(2 * GI, GI, MI));
    }

    /**
     * A stalled transfer has no completion time. Returning a huge number instead would be read as
     * "very slow", which is a different situation and would earn the wrong offer.
     */
    @Test
    public void aStalledTransferHasNoEstimateRatherThanAHugeOne() {
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, GI, 0L));
    }

    @Test
    public void missingInputsGiveNoEstimate() {
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, 0L, MI));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, -1L, MI));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(-1L, GI, MI));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, GI, -1L));
    }

    /** UNKNOWN composes with EtaSmoother, which uses the same sentinel for "no honest estimate". */
    @Test
    public void unknownMatchesTheSmootherSentinel() {
        assertEquals(org.iiab.controller.install.domain.EtaSmoother.UNKNOWN,
                (int) DownloadEta.UNKNOWN);
    }

    // ---- reading the figures out of a real line ------------------------------

    private static final String LINE = "[#2089b0 400MiB/1.0GiB(39%) CN:4 DL:4.5MiB ETA:2m20s]";

    /**
     * The whole point of extracting this from Aria2Manager: the most breakable part of the change
     * is a regex against a third party's output format, and there it was unreachable by any test.
     */
    @Test
    public void pullsCompletedAndTotalOutOfARealLine() {
        assertEquals(400 * MI, Aria2ProgressLine.completedBytes(LINE));
        assertEquals(GI, Aria2ProgressLine.declaredTotalBytes(LINE));
    }

    @Test
    public void theWholeChainFromLineToEstimate() {
        long done = Aria2ProgressLine.completedBytes(LINE);
        long total = Aria2ProgressLine.declaredTotalBytes(LINE);
        long rate = ByteToken.parse("4.5MiB");
        // 1 GiB - 400 MiB left at 4.5 MiB/s
        assertEquals((total - done) / rate, DownloadEta.secondsRemaining(done, total, rate));
    }

    /**
     * The CN:4 is the trap: a bare "4" beside a slash-free field must not be mistaken for a pair.
     * The pattern is anchored on the (NN%) that follows the sizes, which is what excludes it.
     */
    @Test
    public void doesNotMatchTheConnectionCountOrAPath() {
        assertEquals(ByteToken.UNKNOWN, Aria2ProgressLine.completedBytes("[#2089b0 CN:4 DL:4.5MiB]"));
        assertEquals(ByteToken.UNKNOWN,
                Aria2ProgressLine.completedBytes("Downloading /data/foo/bar to /data/baz"));
    }

    @Test
    public void aLineWithoutTheFiguresGivesUnknownRatherThanZero() {
        for (String l : new String[]{null, "", "[#2089b0 FileAlloc:0B/0B]", "no numbers here"}) {
            assertEquals(String.valueOf(l), ByteToken.UNKNOWN, Aria2ProgressLine.completedBytes(l));
            assertEquals(String.valueOf(l), ByteToken.UNKNOWN, Aria2ProgressLine.declaredTotalBytes(l));
        }
    }

    /**
     * Documented, not endorsed: a --check-integrity line carries the same shape, so this parser
     * would read verification progress as transfer progress. Harmless today because the estimate is
     * recomputed on the next real progress line, and noted so it is a known limit rather than a
     * surprise. If it ever matters, require DL: on the same line.
     */
    @Test
    public void aChecksumLineIsCurrentlyIndistinguishable() {
        String chk = "[#2089b0 CHK:512MiB/1.0GiB(50%)]";
        assertEquals(512 * MI, Aria2ProgressLine.completedBytes(chk));
    }

    @Test
    public void unitsWithoutTheBinaryMarkerStillParse() {
        assertEquals(1000_000L, Aria2ProgressLine.completedBytes("[#x 1MB/2MB(50%)]"));
    }
}
