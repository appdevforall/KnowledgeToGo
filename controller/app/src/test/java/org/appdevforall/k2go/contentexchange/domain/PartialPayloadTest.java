package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-577: partial naming, resume start and the sender's range reply. */
public class PartialPayloadTest {

    @Test
    public void nameUsesAppAndBuildId() {
        String n = PartialPayload.name("kiwix", "0c7e2d4a-1b2c-4d5e-8f90-123456789abc");
        assertEquals("partial-kiwix-0c7e2d4a-1b2c-4d5e-8f90-123456789abc", n);
        assertTrue(PartialPayload.isPartial(n));
    }

    @Test
    public void noSafeBuildIdMeansNoPartial() {
        assertNull(PartialPayload.name("kiwix", null));
        assertNull(PartialPayload.name("kiwix", ""));
        assertNull(PartialPayload.name("kiwix", "../evil"));
        assertNull(PartialPayload.name("kiwix", "a/b"));
        assertNull(PartialPayload.name("Kiwix!", "abc"));
        assertNull(PartialPayload.name(null, "abc"));
        assertFalse(PartialPayload.isPartial("0"));
    }

    @Test
    public void resumeFromKeptPrefixOnly() {
        assertEquals(0L, PartialPayload.resumeFrom(0L, 100L));
        assertEquals(40L, PartialPayload.resumeFrom(40L, 100L));
        assertEquals(100L, PartialPayload.resumeFrom(100L, 100L));   // nothing left to fetch
        assertEquals(0L, PartialPayload.resumeFrom(101L, 100L));     // not this item: start over
    }

    @Test
    public void onlyACutExactTransferIsKept() {
        assertTrue(PartialPayload.keepOnFailure(true, false, false));
        assertFalse(PartialPayload.keepOnFailure(false, false, false));   // a tar restarts at 0
        assertFalse(PartialPayload.keepOnFailure(true, true, false));     // the box rejected it
        assertFalse(PartialPayload.keepOnFailure(true, false, true));     // the user cancelled
        assertTrue(PartialPayload.isComplete(100L, 100L));
        assertFalse(PartialPayload.isComplete(99L, 100L));
        assertFalse(PartialPayload.isComplete(101L, 100L));
    }

    @Test
    public void bodyStartAcceptsWholeItemOrMatchingRange() {
        assertEquals(0L, PartialPayload.bodyStart(200, 16777216L, null));
        assertEquals(16777216L,
                PartialPayload.bodyStart(206, 16777216L, "bytes 16777216-333429921/333429922"));
    }

    @Test
    public void bodyStartRejectsOtherReplies() {
        assertEquals(-1L, PartialPayload.bodyStart(206, 100L, "bytes 0-99/200"));
        assertEquals(-1L, PartialPayload.bodyStart(206, 100L, null));
        assertEquals(-1L, PartialPayload.bodyStart(206, 100L, "bytes */200"));
        assertEquals(-1L, PartialPayload.bodyStart(416, 100L, "bytes */200"));
    }
}
