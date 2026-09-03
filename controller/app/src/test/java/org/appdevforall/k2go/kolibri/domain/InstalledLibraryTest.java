package org.appdevforall.k2go.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Unit tests for {@link InstalledLibrary} and {@link InstalledChannel} — chiefly
 * that "we could not ask" never reads as "nothing is installed", and that a
 * half-imported channel is priced by what is missing. Pure JVM.
 */
public class InstalledLibraryTest {

    private static final String KHAN = "c9d7f950ab6b5a1199e3d6c10d7f0103";
    private static final String STORY = "f9d3e0e46ea25789bbed672ff6a399ed";
    private static final String ABSENT = "6277aa0c44235435acdc8a9ed98f466b";

    private static final long GB = 1024L * 1024L * 1024L;

    // ---- unknown is not empty ---------------------------------------------

    @Test
    public void aListingThatCouldNotBeReadIsNotAnEmptyDevice() {
        InstalledLibrary l = InstalledLibrary.unknown();
        assertFalse(l.isKnown());
        assertFalse(l.has(KHAN));
        // The difference that matters: an unknown library must not re-price
        // anything, so a channel still costs its published size.
        assertEquals(4 * GB, l.costOf(KHAN, 4 * GB));
    }

    @Test
    public void aNullListingIsUnknownRatherThanEmpty() {
        assertFalse(InstalledLibrary.of(null).isKnown());
    }

    @Test
    public void anEmptyListingIsAnObservation() {
        InstalledLibrary l = InstalledLibrary.empty();
        assertTrue(l.isKnown());
        assertEquals(0, l.size());
        assertEquals(InstalledLibrary.empty().isKnown(),
                InstalledLibrary.of(Collections.<InstalledChannel>emptyList()).isKnown());
    }

    // ---- what a channel actually costs -------------------------------------

    @Test
    public void aCompleteChannelCostsNothing() {
        InstalledLibrary l = InstalledLibrary.of(Collections.singletonList(
                InstalledChannel.of(KHAN, "Khan", 3, 100, 100, 4 * GB, 4 * GB)));
        assertTrue(l.isComplete(KHAN));
        assertEquals(0L, l.costOf(KHAN, 4 * GB));
    }

    @Test
    public void aHalfImportedChannelCostsWhatIsMissing() {
        // The difference between "does not fit" and "fits easily" on a small device.
        InstalledLibrary l = InstalledLibrary.of(Collections.singletonList(
                InstalledChannel.of(KHAN, "Khan", 3, 100, 80, 10 * GB, 8 * GB)));
        assertFalse(l.isComplete(KHAN));
        assertTrue(l.find(KHAN).isPartial());
        assertEquals(2 * GB, l.costOf(KHAN, 10 * GB));
    }

    @Test
    public void aChannelTheDeviceDoesNotHaveCostsItsPublishedSize() {
        InstalledLibrary l = InstalledLibrary.of(Collections.singletonList(
                InstalledChannel.of(KHAN, "Khan", 3, 100, 100, 4 * GB, 4 * GB)));
        assertNull(l.find(ABSENT));
        assertEquals(7 * GB, l.costOf(ABSENT, 7 * GB));
    }

    @Test
    public void aMetadataOnlyImportStillCostsTheWholeChannel() {
        // Listed, catalogue known, not one file on disk. Quoting zero here would be
        // the worst possible answer: it looks installed and holds nothing.
        InstalledChannel meta = InstalledChannel.of(STORY, "Storybooks", 1, 0, 0, 0L, 0L);
        InstalledLibrary l = InstalledLibrary.of(Collections.singletonList(meta));

        assertTrue(meta.isMetadataOnly());
        assertFalse(meta.isComplete());
        assertTrue(l.has(STORY));
        assertEquals(3 * GB, l.costOf(STORY, 3 * GB));
    }

    // ---- the record itself ---------------------------------------------------

    @Test
    public void countsCannotGoIncoherent() {
        // A row claiming more available than exist would make "what is missing"
        // negative, and the arithmetic is what the storage bar is built on.
        InstalledChannel c = InstalledChannel.of(KHAN, "Khan", 3, 10, 99, 5 * GB, 9 * GB);
        assertEquals(10, c.filesAvailable());
        assertEquals(5 * GB, c.bytesAvailable());
        assertEquals(0L, c.bytesRemaining());
        assertTrue(c.isComplete());
    }

    @Test
    public void negativesAreClampedRatherThanCarried() {
        InstalledChannel c = InstalledChannel.of(KHAN, "  Khan  ", -2, -5, -5, -1L, -1L);
        assertEquals("Khan", c.name());
        assertEquals(0, c.version());
        assertEquals(0, c.filesTotal());
        assertEquals(0L, c.bytesRemaining());
        // No files at all is metadata-only, and metadata-only is never complete.
        assertFalse(c.isComplete());
        assertTrue(c.isMetadataOnly());
    }

    @Test
    public void aRowWithAnUnusableIdIsRejected() {
        assertNull(InstalledChannel.of("not-an-id", "x", 1, 1, 1, 1L, 1L));
        assertNull(InstalledChannel.of(null, "x", 1, 1, 1, 1L, 1L));
    }

    @Test
    public void unusableRowsAreDroppedFromTheListing() {
        InstalledLibrary l = InstalledLibrary.of(Arrays.asList(
                InstalledChannel.of(KHAN, "Khan", 3, 1, 1, GB, GB),
                null,
                InstalledChannel.of("nope", "Broken", 1, 1, 1, GB, GB)));
        assertEquals(1, l.size());
        assertTrue(l.has(KHAN));
    }

    @Test
    public void aDashedIdFindsTheSameChannel() {
        InstalledLibrary l = InstalledLibrary.of(Collections.singletonList(
                InstalledChannel.of(KHAN, "Khan", 3, 1, 1, GB, GB)));
        assertTrue(l.has("c9d7f950-ab6b-5a11-99e3-d6c10d7f0103"));
    }
}
