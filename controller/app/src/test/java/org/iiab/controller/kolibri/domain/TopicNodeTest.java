package org.iiab.controller.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Unit tests for {@link TopicNode} — the type that decides when a subtree size
 * may be trusted. Getting this wrong under-counts the download and the user
 * finds out when the disk fills. Pure JVM, no Android dependencies.
 */
public class TopicNodeTest {

    private static final String ROOT = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String A = "23a7dc9c73635cd2abbd3e8aab13c3ca";
    private static final String B = "a3eac3983b085e1594eeb18d1f173260";

    private static TopicNode leaf(String id, long bytes) {
        return TopicNode.of(id, "leaf", "video", true, bytes, 0, null);
    }

    @Test
    public void aLeafsOwnSizeIsItsSubtreeSize() {
        TopicNode n = leaf(A, 5000L);
        assertTrue(n.isLeaf());
        assertEquals(5000L, n.ownBytes());
        assertTrue(n.hasSubtreeSize());
        assertEquals(5000L, n.subtreeBytes());
    }

    @Test
    public void aTopicSumsItsChildren() {
        TopicNode t = TopicNode.of(ROOT, "Topic", "topic", false, 0L, 2,
                Arrays.asList(leaf(A, 100L), leaf(B, 250L)));
        assertTrue(t.isTopic());
        assertTrue(t.hasSubtreeSize());
        assertEquals(350L, t.subtreeBytes());
    }

    @Test
    public void aTopicAddsItsOwnFilesToTheChildrenTotal() {
        TopicNode t = TopicNode.of(ROOT, "Topic", "topic", false, 40L, 1,
                Collections.singletonList(leaf(A, 100L)));
        assertEquals(140L, t.subtreeBytes());
    }

    @Test
    public void oneChildOfUnknownSizeMakesTheWholeSubtreeUnknown() {
        // Summing the known ones would report a number smaller than the truth,
        // and a caller would plan disk against it. Unknown is the honest answer.
        TopicNode unknown = TopicNode.of(A, "Sub", "topic", false, 0L, 5, null);
        assertFalse(unknown.hasSubtreeSize());

        TopicNode t = TopicNode.of(ROOT, "Topic", "topic", false, 0L, 6,
                Arrays.asList(leaf(B, 900L), unknown));
        assertFalse(t.hasSubtreeSize());
        assertEquals(0L, t.subtreeBytes());
    }

    @Test
    public void pagedChildrenMakeTheSubtreeSizeUnknown() {
        // Studio pages a wide level and signals it with a non-null children.more.
        // The children that did arrive are real, but their sum is not the total.
        TopicNode t = TopicNode.of(ROOT, "Topic", "topic", false, 0L, 400,
                Arrays.asList(leaf(A, 100L), leaf(B, 100L)), false);
        assertEquals(2, t.children().size());
        assertFalse(t.hasSubtreeSize());
        assertEquals(0L, t.subtreeBytes());
    }

    @Test
    public void aTopicWithNoChildrenHasNoKnownSize() {
        // Not the same as "empty topic, 0 bytes": it means the level was never
        // fetched. Reporting 0 would let it pass a fit check it never earned.
        TopicNode t = TopicNode.of(ROOT, "Topic", "topic", false, 0L, 12, null);
        assertFalse(t.hasSubtreeSize());
        assertFalse(t.hasChildren());
    }

    @Test
    public void nestedTopicsAccumulate() {
        TopicNode inner = TopicNode.of(A, "Inner", "topic", false, 0L, 1,
                Collections.singletonList(leaf(B, 70L)));
        TopicNode outer = TopicNode.of(ROOT, "Outer", "topic", false, 0L, 2,
                Collections.singletonList(inner));
        assertTrue(outer.hasSubtreeSize());
        assertEquals(70L, outer.subtreeBytes());
    }

    @Test
    public void anInvalidIdIsRejectedRatherThanBuiltUnusable() {
        assertNull(TopicNode.of("not-an-id", "x", "topic", false, 0L, 0, null));
        assertNull(TopicNode.of("", "x", "topic", false, 0L, 0, null));
        assertNull(TopicNode.of(null, "x", "topic", false, 0L, 0, null));
    }

    @Test
    public void idsAreNormalisedSoTheHyphenatedFormMatches() {
        TopicNode n = TopicNode.of("23a7dc9c-7363-5cd2-abbd-3e8aab13c3ca",
                "x", "topic", false, 0L, 0, null);
        assertEquals(A, n.id());
    }

    @Test
    public void nullChildrenAreDroppedNotCounted() {
        TopicNode t = TopicNode.of(ROOT, "Topic", "topic", false, 0L, 1,
                Arrays.asList(leaf(A, 10L), null));
        assertEquals(1, t.children().size());
        assertEquals(10L, t.subtreeBytes());
    }

    @Test
    public void negativeOwnBytesAreClampedNotPropagated() {
        assertEquals(0L, leaf(A, -500L).ownBytes());
    }

    @Test
    public void onlyTopicKindCountsAsATopic() {
        assertTrue(TopicNode.of(ROOT, "x", "topic", false, 0L, 0, null).isTopic());
        assertFalse(leaf(A, 1L).isTopic());
    }

    @Test
    public void childrenAreNotMutableFromOutside() {
        TopicNode t = TopicNode.of(ROOT, "Topic", "topic", false, 0L, 1,
                Collections.singletonList(leaf(A, 10L)));
        try {
            t.children().add(leaf(B, 1L));
            fail("expected the child list to be immutable");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }

    @Test
    public void descendantCountIsCarriedAndClamped() {
        assertEquals(179, TopicNode.of(ROOT, "x", "topic", false, 0L, 179, null)
                .descendantCount());
        assertEquals(0, TopicNode.of(ROOT, "x", "topic", false, 0L, -3, null)
                .descendantCount());
    }
}
