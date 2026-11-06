package org.appdevforall.k2go.sync.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link RsyncProgress} — parsing rsync --info=progress2 and
 * --stats output (S14 step 1). Pure JVM, no Android dependencies.
 */
public class RsyncProgressTest {

    @Test
    public void parsesProgressLine() {
        RsyncProgress p = RsyncProgress.parse("     32,768  45%  12.34MB/s    0:00:12");
        assertNotNull(p);
        assertEquals(32768L, p.bytes);
        assertEquals(45, p.percent);
        assertEquals("12.34MB/s", p.speed);
        assertEquals("0:00:12", p.eta);
    }

    // ADFA-5160: the leading byte column is the numerator the caller anchors to a known total.
    @Test
    public void parsesLeadingTransferredBytesWithSeparators() {
        RsyncProgress p = RsyncProgress.parse("1,234,567,890  88%  40.00MB/s    0:00:03");
        assertNotNull(p);
        assertEquals(1234567890L, p.bytes);
        assertEquals(88, p.percent);
    }

    // K2GO-550: rsync 3.5.2 writes the byte column with a "B" unit.
    @Test
    public void parsesByteColumnWithUnit() {
        RsyncProgress p = RsyncProgress.parse("    122,191,872B  58%  122MB/s    0:00:00");
        assertNotNull(p);
        assertEquals(122191872L, p.bytes);
        assertEquals(58, p.percent);
        assertEquals("122MB/s", p.speed);
        assertEquals("0:00:00", p.eta);
    }

    // Real rsync 3.5.2 output (OnePlus 7T, shipped librsync.so): first sample and a final line
    // with the (xfr#, to-chk) suffix.
    @Test
    public void parsesRsync352Lines() {
        RsyncProgress first = RsyncProgress.parse("         32,768B   0%       0B/s    0:00:00  ");
        assertNotNull(first);
        assertEquals(32768L, first.bytes);
        assertEquals("0B/s", first.speed);

        RsyncProgress last = RsyncProgress.parse(
                "     30,000,000B 100%    143MB/s    0:00:00 (xfr#1, to-chk=0/2)");
        assertNotNull(last);
        assertEquals(30000000L, last.bytes);
        assertEquals(100, last.percent);
        assertEquals("143MB/s", last.speed);
    }

    @Test
    public void parsesRsync352StatsLine() {
        assertEquals(30000000L, RsyncProgress.parseTransferredBytes(
                "Total transferred file size: 30,000,000 bytes", -1L));
    }

    @Test
    public void returnsNullWhenNoProgressToken() {
        assertNull(RsyncProgress.parse("sending incremental file list"));
        assertNull(RsyncProgress.parse(""));
        assertNull(RsyncProgress.parse(null));
    }

    @Test
    public void parsesTransferredBytesStrippingSeparators() {
        long b = RsyncProgress.parseTransferredBytes(
                "Total transferred file size: 1,234,567 bytes", -1L);
        assertEquals(1234567L, b);
    }

    @Test
    public void returnsFallbackWhenStatsLineAbsent() {
        assertEquals(99L, RsyncProgress.parseTransferredBytes("some other line", 99L));
        assertEquals(0L, RsyncProgress.parseTransferredBytes(null, 0L));
    }

    // ADFA-5160: whole-set ETA formatting, rsync's H:MM:SS.
    @Test
    public void formatsEtaAsHoursMinutesSeconds() {
        assertEquals("0:00:12", RsyncProgress.formatEta(12));
        assertEquals("0:01:15", RsyncProgress.formatEta(75));
        assertEquals("1:02:05", RsyncProgress.formatEta(3725));
        assertEquals("0:00:00", RsyncProgress.formatEta(-5));
    }
}
