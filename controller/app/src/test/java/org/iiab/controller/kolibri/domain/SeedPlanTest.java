package org.iiab.controller.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link SeedPlan} — what is queued and whether it fits.
 * Pure JVM, no Android dependencies.
 */
public class SeedPlanTest {

    private static final String STORYBOOK = "f9d3e0e46ea25789bbed672ff6a399ed";
    private static final String KHAN = "c9d7f950ab6b5a1199e3d6c10d7f0103";
    private static final String NODE = "6277aa0c44235435acdc8a9ed98f466b";

    private static final long GB = 1024L * 1024L * 1024L;

    private static Map<String, Long> sizes(Object... pairs) {
        Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void anEmptyPlanIsEmptyAndFitsAnywhere() {
        SeedPlan p = SeedPlan.empty();
        assertTrue(p.isEmpty());
        assertEquals(0, p.size());
        assertEquals(0L, p.estimatedBytes());
        assertTrue(p.isEstimateExact());
        assertEquals(Boolean.TRUE, p.fitsIn(0L));
    }

    @Test
    public void nullOrEmptySelectionsYieldAnEmptyPlan() {
        assertTrue(SeedPlan.of(null, null).isEmpty());
        assertTrue(SeedPlan.of(Collections.<ChannelSelection>emptyList(), null).isEmpty());
    }

    @Test
    public void sumsTheKnownSizes() {
        SeedPlan p = SeedPlan.of(
                Arrays.asList(ChannelSelection.wholeChannel(STORYBOOK),
                        ChannelSelection.wholeChannel(KHAN)),
                sizes(STORYBOOK, 8L * GB, KHAN, 2L * GB));
        assertEquals(2, p.size());
        assertEquals(10L * GB, p.estimatedBytes());
        assertTrue(p.isEstimateExact());
    }

    @Test
    public void aChannelQueuedTwiceIsKeptOnce() {
        // Importing one channel concurrently only contends on the same SQLite file.
        SeedPlan p = SeedPlan.of(
                Arrays.asList(ChannelSelection.wholeChannel(STORYBOOK),
                        ChannelSelection.ofSubtrees(STORYBOOK, Collections.singletonList(NODE))),
                sizes(STORYBOOK, 8L * GB));
        assertEquals(1, p.size());
        // The later entry wins.
        assertFalse(p.selections().get(0).isWholeChannel());
    }

    @Test
    public void firstAppearanceOrderIsKept() {
        SeedPlan p = SeedPlan.of(
                Arrays.asList(ChannelSelection.wholeChannel(KHAN),
                        ChannelSelection.wholeChannel(STORYBOOK)),
                null);
        assertEquals(KHAN, p.selections().get(0).channelId());
        assertEquals(STORYBOOK, p.selections().get(1).channelId());
    }

    @Test
    public void anUnknownSizeMakesTheEstimateInexact() {
        SeedPlan p = SeedPlan.of(
                Arrays.asList(ChannelSelection.wholeChannel(STORYBOOK),
                        ChannelSelection.wholeChannel(KHAN)),
                sizes(STORYBOOK, 8L * GB));
        assertEquals(1, p.channelsWithUnknownSize());
        assertEquals(8L * GB, p.estimatedBytes());
        assertFalse(p.isEstimateExact());
    }

    @Test
    public void nonPositiveSizesCountAsUnknown() {
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)),
                sizes(STORYBOOK, 0L));
        assertEquals(1, p.channelsWithUnknownSize());
        assertFalse(p.isEstimateExact());
    }

    @Test
    public void aPartialSelectionMakesTheEstimateInexactEvenWithAKnownSize() {
        // The catalog only carries whole-channel sizes, so a subtree total is an
        // upper bound, not a figure.
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(
                        ChannelSelection.ofSubtrees(STORYBOOK, Collections.singletonList(NODE))),
                sizes(STORYBOOK, 8L * GB));
        assertEquals(0, p.channelsWithUnknownSize());
        assertFalse(p.isEstimateExact());
    }

    @Test
    public void requiredBytesAddsHeadRoom() {
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)),
                sizes(STORYBOOK, 1000L));
        assertEquals(1100L, p.requiredBytes());          // default 110 %
        assertEquals(1500L, p.requiredBytes(150));
    }

    @Test
    public void requiredBytesScalesTheRemainderInsteadOfTruncatingIt() {
        // A size divisible by 100 cannot tell "* margin / 100" from "/ 100 * margin",
        // so the original test passed while the second, wrong, ordering was in place.
        // 1099 does distinguish them: truncating first gives 1100, scaling gives 1208.
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)),
                sizes(STORYBOOK, 1099L));
        assertEquals(1208L, p.requiredBytes());
        assertEquals(1648L, p.requiredBytes(150));
    }

    @Test
    public void requiredBytesDoesNotOverflowOnACatalogSizedPlan() {
        // The whole public catalog is ~775 GB; multiplying before dividing has to stay
        // inside long. 8.3e11 * 150 is 1.2e14, well under the 9.2e18 ceiling.
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)),
                sizes(STORYBOOK, 775L * GB));
        long required = p.requiredBytes(150);
        assertTrue("overflowed to a negative", required > 0L);
        assertEquals(775L * GB / 100L * 150L, required);
    }

    @Test
    public void aMarginBelowOneHundredIsClampedNotHonoured() {
        // Planning to use less space than the content needs is never right.
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)),
                sizes(STORYBOOK, 1000L));
        assertEquals(1000L, p.requiredBytes(50));
        assertEquals(1000L, p.requiredBytes(0));
    }

    @Test
    public void fitsWhenThereIsRoomForTheMarginToo() {
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)),
                sizes(STORYBOOK, 1000L));
        assertEquals(Boolean.TRUE, p.fitsIn(1100L));
        assertEquals(Boolean.FALSE, p.fitsIn(1099L));
    }

    @Test
    public void unknownFreeSpaceIsUnknownNotFalse() {
        // A caller that read null as "does not fit" would refuse to seed when it
        // simply could not measure.
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)),
                sizes(STORYBOOK, 1000L));
        assertNull(p.fitsIn(null));
        assertNull(p.fitsIn(-1L));
    }

    @Test
    public void anInexactEstimateCannotDecideFit() {
        // The case a phone needs most: a small subtree of a very large channel.
        // Answering FALSE here would block it on an over-estimate.
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(
                        ChannelSelection.ofSubtrees(STORYBOOK, Collections.singletonList(NODE))),
                sizes(STORYBOOK, 8L * GB));
        assertNull(p.fitsIn(1L * GB));
    }

    @Test
    public void selectionsAreNotMutableFromOutside() {
        SeedPlan p = SeedPlan.of(
                Collections.singletonList(ChannelSelection.wholeChannel(STORYBOOK)), null);
        try {
            p.selections().add(ChannelSelection.wholeChannel(KHAN));
            throw new AssertionError("expected the selection list to be immutable");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }

    @Test
    public void bytesForAnUnknownChannelIsZeroNotAnError() {
        SeedPlan p = SeedPlan.empty();
        assertEquals(0L, p.bytesFor(STORYBOOK));
        assertEquals(0L, p.bytesFor(null));
    }
}
