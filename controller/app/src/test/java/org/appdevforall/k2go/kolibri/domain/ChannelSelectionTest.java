package org.appdevforall.k2go.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link ChannelSelection} — the type that keeps "the whole
 * channel" and "nothing at all" from collapsing into each other, which in
 * Kolibri's API is the difference between omitting node_ids and sending an empty
 * list. Pure JVM, no Android dependencies.
 */
public class ChannelSelectionTest {

    private static final String CHANNEL = "f9d3e0e46ea25789bbed672ff6a399ed";
    private static final String NODE_A = "6277aa0c44235435acdc8a9ed98f466b";
    private static final String NODE_B = "aaaa1111bbbb2222cccc3333dddd4444";

    @Test
    public void wholeChannelHasNoNodeIds() {
        ChannelSelection s = ChannelSelection.wholeChannel(CHANNEL);
        assertEquals(CHANNEL, s.channelId());
        assertTrue(s.isWholeChannel());
        assertTrue(s.nodeIds().isEmpty());
    }

    @Test
    public void subtreeSelectionKeepsTheNodes() {
        ChannelSelection s = ChannelSelection.ofSubtrees(CHANNEL, Arrays.asList(NODE_A, NODE_B));
        assertFalse(s.isWholeChannel());
        assertEquals(Arrays.asList(NODE_A, NODE_B), s.nodeIds());
    }

    @Test
    public void anEmptySubtreeListIsRejectedRatherThanDownloadingNothing() {
        // Kolibri would accept node_ids: [] and finish successfully having
        // transferred zero bytes. That silent no-op is what this rejects.
        try {
            ChannelSelection.ofSubtrees(CHANNEL, Collections.<String>emptyList());
            fail("expected an empty subtree list to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("wholeChannel"));
        }
    }

    @Test
    public void aSelectionOfOnlyInvalidNodesIsRejected() {
        // Same silent no-op by another route: ids that all fail validation would
        // leave an empty list behind.
        try {
            ChannelSelection.ofSubtrees(CHANNEL, Arrays.asList("bisan-sukod", "nope", ""));
            fail("expected an all-invalid subtree list to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no valid node id"));
        }
    }

    @Test
    public void invalidNodesAreDroppedWhenAtLeastOneSurvives() {
        ChannelSelection s = ChannelSelection.ofSubtrees(
                CHANNEL, Arrays.asList("nope", NODE_A, ""));
        assertEquals(Collections.singletonList(NODE_A), s.nodeIds());
    }

    @Test
    public void nodeIdsAreNormalisedAndDeduplicated() {
        String dashed = "6277aa0c-4423-5435-acdc-8a9ed98f466b";   // same as NODE_A
        ChannelSelection s = ChannelSelection.ofSubtrees(
                CHANNEL, Arrays.asList(NODE_A, dashed, NODE_A.toUpperCase(), NODE_B));
        // Three spellings of one node collapse to one entry, order preserved.
        assertEquals(Arrays.asList(NODE_A, NODE_B), s.nodeIds());
    }

    @Test
    public void channelIdIsNormalised() {
        ChannelSelection s = ChannelSelection.wholeChannel(
                "F9D3E0E4-6EA2-5789-BBED-672FF6A399ED");
        assertEquals(CHANNEL, s.channelId());
    }

    @Test
    public void aTokenAsChannelIdSaysSoInsteadOfJustFailing() {
        try {
            ChannelSelection.wholeChannel("bisan-sukod");
            fail("expected a token to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("token"));
            assertTrue(expected.getMessage().contains("resolve"));
        }
    }

    @Test
    public void anInvalidChannelIdIsRejected() {
        try {
            ChannelSelection.wholeChannel("not-an-id");
            fail("expected an invalid channel id to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid channel id"));
        }
    }

    @Test
    public void nullNodeListIsRejected() {
        try {
            ChannelSelection.ofSubtrees(CHANNEL, null);
            fail("expected a null subtree list to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("empty subtree selection"));
        }
    }

    @Test
    public void thumbnailsAreOnlyWorthAskingForOnAPartialSelection() {
        // A whole channel already brings its topic artwork; asking again is pure
        // extra download. A partial one would otherwise browse with gaps.
        assertFalse(ChannelSelection.wholeChannel(CHANNEL).wantsAllThumbnails());
        assertTrue(ChannelSelection.ofSubtrees(CHANNEL, Collections.singletonList(NODE_A))
                .wantsAllThumbnails());
    }

    @Test
    public void nodeIdsAreNotMutableFromOutside() {
        ChannelSelection s = ChannelSelection.ofSubtrees(
                CHANNEL, Collections.singletonList(NODE_A));
        try {
            s.nodeIds().add(NODE_B);
            fail("expected the node id list to be immutable");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }

    @Test
    public void mutatingTheInputListLaterDoesNotChangeTheSelection() {
        List<String> input = new ArrayList<>();
        input.add(NODE_A);
        ChannelSelection s = ChannelSelection.ofSubtrees(CHANNEL, input);
        input.add(NODE_B);
        assertEquals(Collections.singletonList(NODE_A), s.nodeIds());
    }

    @Test
    public void equalityIgnoresSpelling() {
        assertEquals(ChannelSelection.wholeChannel(CHANNEL),
                ChannelSelection.wholeChannel(CHANNEL.toUpperCase()));
        assertEquals(ChannelSelection.wholeChannel(CHANNEL).hashCode(),
                ChannelSelection.wholeChannel(CHANNEL.toUpperCase()).hashCode());
    }

    @Test
    public void wholeChannelIsNotEqualToAPartialOne() {
        assertNotEquals(ChannelSelection.wholeChannel(CHANNEL),
                ChannelSelection.ofSubtrees(CHANNEL, Collections.singletonList(NODE_A)));
    }
}
