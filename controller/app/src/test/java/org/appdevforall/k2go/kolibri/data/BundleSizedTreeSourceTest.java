package org.appdevforall.k2go.kolibri.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.kolibri.domain.TopicNode;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** K2GO-476: Studio levels take the bundle's folder sizes, dated by the older source. */
public class BundleSizedTreeSourceTest {

    private static final String ROOT = "11111111111111111111111111111111";
    private static final String A = "22222222222222222222222222222222";
    private static final String B = "33333333333333333333333333333333";
    private static final long NOW = 2_000_000L;
    private static final long BUNDLE = 1_000_000L;

    /** A Studio-like level: folder A with an unknown size (paged), folder B known. */
    private static TopicNode level() {
        TopicNode a = TopicNode.of(A, "A", "topic", false, 0L, 4,
                Collections.<TopicNode>emptyList(), false);
        TopicNode leaf = TopicNode.of("44444444444444444444444444444444", "v", "video", true, 50L, 0,
                Collections.<TopicNode>emptyList());
        TopicNode b = TopicNode.of(B, "B", "topic", false, 0L, 1, Collections.singletonList(leaf));
        return TopicNode.of(ROOT, "root", "topic", false, 0L, 6, Arrays.asList(a, b))
                .withAsOf(NOW);
    }

    private static BundleSizedTreeSource.Sizes bundle(long aBytes, long generated) {
        return new BundleSizedTreeSource.Sizes() {
            @Override public long bytesOf(String id) { return A.equals(id) ? aBytes : -1L; }
            @Override public long generatedMs() { return generated; }
        };
    }

    @Test
    public void anUnknownFolderTakesTheBundleSizeAndTheLevelTheOlderDate() {
        TopicNode out = new BundleSizedTreeSource(id -> level(), bundle(700L, BUNDLE)).fetchTree(ROOT);
        TopicNode a = out.children().get(0);
        assertTrue(a.hasSubtreeSize());
        assertEquals(700L, a.subtreeBytes());
        assertEquals(50L, out.children().get(1).subtreeBytes());   // known size untouched
        assertEquals(BUNDLE, out.asOfMs());
    }

    @Test
    public void withNothingToFillTheLiveLevelIsReturnedAsIs() {
        TopicNode live = level();
        TopicNode out = new BundleSizedTreeSource(id -> live, bundle(-1L, BUNDLE)).fetchTree(ROOT);
        assertSame(live, out);
        assertEquals(NOW, out.asOfMs());
        assertFalse(out.children().get(0).hasSubtreeSize());
    }

    @Test
    public void anUndatedBundleLeavesTheLevelUndated() {
        TopicNode out = new BundleSizedTreeSource(id -> level(), bundle(700L, 0L)).fetchTree(ROOT);
        assertEquals(0L, out.asOfMs());
    }
}
