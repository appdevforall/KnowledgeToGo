package org.appdevforall.k2go.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link PickedSubtrees} — the ancestry rule that keeps a partial
 * selection disjoint, and therefore its total honest. Pure JVM.
 */
public class PickedSubtreesTest {

    private static final String CHANNEL = "c150ea1d69495d37b5b0ac6f017e9bfb";

    private static final String MATH = "6277aa0c44235435acdc8a9ed98f466b";
    private static final String FRACTIONS = "f9d3e0e46ea25789bbed672ff6a399ed";
    private static final String HALVES = "c9d7f950ab6b5a1199e3d6c10d7f0103";
    private static final String SCIENCE = "0a1b2c3d4e5f60718293a4b5c6d7e8f9";

    private static final long MB = 1024L * 1024L;

    @Test
    public void anEmptySelectionMeansTheWholeChannel() {
        PickedSubtrees p = PickedSubtrees.empty();
        assertTrue(p.isEmpty());
        assertEquals(0, p.size());
        assertEquals(0L, p.totalBytes());
        assertFalse(p.hasUnknownSize());
        // The important part: never node_ids: [], which Kolibri reads as zero nodes.
        assertTrue(p.toSelection(CHANNEL).isWholeChannel());
    }

    @Test
    public void picksAreKeptInTheOrderTheyWereMade() {
        PickedSubtrees p = PickedSubtrees.empty()
                .add(SCIENCE, Collections.<String>emptyList(), 10 * MB, true)
                .add(MATH, Collections.<String>emptyList(), 20 * MB, true);
        assertEquals(Arrays.asList(SCIENCE, MATH), p.nodeIds());
        assertEquals(30 * MB, p.totalBytes());
    }

    @Test
    public void pickingUnderAnAlreadyPickedTopicChangesNothing() {
        PickedSubtrees withMath = PickedSubtrees.empty()
                .add(MATH, Collections.<String>emptyList(), 20 * MB, true);

        // Fractions is already coming: Kolibri expands MATH to its whole subtree.
        PickedSubtrees after = withMath.add(FRACTIONS, Arrays.asList(MATH), 5 * MB, true);

        assertSame(withMath, after);
        assertEquals(Arrays.asList(MATH), after.nodeIds());
        // And crucially the total did not grow by a subtree it already contained.
        assertEquals(20 * MB, after.totalBytes());
    }

    @Test
    public void pickingAnAncestorReplacesTheDescendantsItCovers() {
        // Two SIBLINGS under Mathematics, not one nested in the other: picking a node
        // inside an already-picked one is refused outright, so the only way to end up
        // with several descendants of the same parent is to pick them side by side.
        PickedSubtrees p = PickedSubtrees.empty()
                .add(FRACTIONS, Arrays.asList(MATH), 5 * MB, true)
                .add(HALVES, Arrays.asList(MATH), 2 * MB, true)
                .add(SCIENCE, Collections.<String>emptyList(), 7 * MB, true);

        assertEquals(3, p.size());
        assertEquals(14 * MB, p.totalBytes());

        // Widening to the parent must drop both siblings, or the total counts them
        // twice and the request carries ids that contradict their own parent.
        PickedSubtrees widened = p.add(MATH, Collections.<String>emptyList(), 20 * MB, true);

        assertEquals(Arrays.asList(SCIENCE, MATH), widened.nodeIds());
        assertEquals(27 * MB, widened.totalBytes());
    }

    @Test
    public void aSecondBranchUnderAPickedOneIsRefusedNotStacked() {
        // The case that made the sibling test above necessary, stated on its own:
        // Fractions is picked, so Halves — which lives inside it — adds nothing.
        PickedSubtrees p = PickedSubtrees.empty()
                .add(FRACTIONS, Arrays.asList(MATH), 5 * MB, true);

        PickedSubtrees after = p.add(HALVES, Arrays.asList(MATH, FRACTIONS), 2 * MB, true);

        assertSame(p, after);
        assertEquals(1, after.size());
        assertEquals(5 * MB, after.totalBytes());
        assertTrue(after.covers(HALVES, Arrays.asList(MATH, FRACTIONS)));
    }

    @Test
    public void coversDistinguishesBeingPickedFromBeingIncluded() {
        PickedSubtrees p = PickedSubtrees.empty()
                .add(MATH, Collections.<String>emptyList(), 20 * MB, true);

        assertTrue(p.contains(MATH));
        assertTrue(p.covers(MATH, Collections.<String>emptyList()));

        // Fractions is downloaded but is not a member: a checkbox must be able to
        // show "included" without offering to un-tick something that was never set.
        assertFalse(p.contains(FRACTIONS));
        assertTrue(p.covers(FRACTIONS, Arrays.asList(MATH)));

        assertFalse(p.covers(SCIENCE, Collections.<String>emptyList()));
    }

    @Test
    public void removingTakesOnlyThatNode() {
        PickedSubtrees p = PickedSubtrees.empty()
                .add(MATH, Collections.<String>emptyList(), 20 * MB, true)
                .add(SCIENCE, Collections.<String>emptyList(), 7 * MB, true)
                .remove(MATH);

        assertEquals(Arrays.asList(SCIENCE), p.nodeIds());
        assertEquals(7 * MB, p.totalBytes());
    }

    @Test
    public void removingSomethingNotPickedIsANoOp() {
        PickedSubtrees p = PickedSubtrees.empty()
                .add(MATH, Collections.<String>emptyList(), 20 * MB, true);
        assertSame(p, p.remove(SCIENCE));
        assertSame(p, p.remove("not-an-id"));
    }

    @Test
    public void anUnsizedPickMakesTheTotalAFloorRatherThanZero() {
        PickedSubtrees p = PickedSubtrees.empty()
                .add(MATH, Collections.<String>emptyList(), 20 * MB, true)
                .add(SCIENCE, Collections.<String>emptyList(), 0L, false);

        assertTrue(p.hasUnknownSize());
        // The known part still counts; the unknown one simply is not invented.
        assertEquals(20 * MB, p.totalBytes());
        assertEquals(2, p.size());
    }

    @Test
    public void anUnusableNodeIdIsIgnoredRatherThanStored() {
        PickedSubtrees p = PickedSubtrees.empty();
        assertSame(p, p.add(null, Collections.<String>emptyList(), MB, true));
        assertSame(p, p.add("", Collections.<String>emptyList(), MB, true));
        assertSame(p, p.add("zzzz", Collections.<String>emptyList(), MB, true));
        assertTrue(p.isEmpty());
    }

    @Test
    public void dashedIdsAndUnusableAncestorsAreNormalisedAway() {
        String dashedMath = "6277aa0c-4423-5435-acdc-8a9ed98f466b";
        PickedSubtrees p = PickedSubtrees.empty()
                .add(dashedMath, Arrays.asList("", null, SCIENCE), MB, true);

        // Stored canonically, so a later dashed reference finds the same node.
        assertEquals(Arrays.asList(MATH), p.nodeIds());
        assertTrue(p.contains(dashedMath));

        // The one usable ancestor survived the junk, so the rule still applies
        // through it: picking SCIENCE now widens and absorbs MATH.
        PickedSubtrees widened = p.add(SCIENCE, Collections.<String>emptyList(), 9 * MB, true);
        assertEquals(Arrays.asList(SCIENCE), widened.nodeIds());
    }

    @Test
    public void aNonEmptySelectionBecomesSubtreesForTheChannel() {
        PickedSubtrees p = PickedSubtrees.empty()
                .add(MATH, Collections.<String>emptyList(), 20 * MB, true);

        ChannelSelection sel = p.toSelection(CHANNEL);
        assertFalse(sel.isWholeChannel());
        assertEquals(CHANNEL, sel.channelId());
        List<String> ids = sel.nodeIds();
        assertEquals(1, ids.size());
        assertEquals(MATH, ids.get(0));
        // A partial import needs the topic artwork the unpicked branches would have
        // brought, or the browsing screen looks broken.
        assertTrue(sel.wantsAllThumbnails());
    }
}
