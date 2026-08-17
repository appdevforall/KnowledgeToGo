package org.iiab.controller.kolibri.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.kolibri.domain.TopicNode;
import org.junit.Test;

/**
 * Unit tests for {@link BundledTreeIndex} — the flat offline tree served one level at a
 * time. Pure JVM (real org.json on the test classpath), no Android. (ADFA-5094)
 */
public class BundledTreeIndexTest {

    private static final String ROOT = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String A = "23a7dc9c73635cd2abbd3e8aab13c3ca";
    private static final String B = "a3eac3983b085e1594eeb18d1f173260";
    private static final String D = "0123456789abcdef0123456789abcdef";
    private static final String ABSENT = "ffffffffffffffffffffffffffffffff";

    private static String row(String id, String parent, String title, int count, long bytes,
                              int dcount, long dbytes) {
        return "{\"id\":\"" + id + "\",\"parent\":\"" + parent + "\",\"title\":\"" + title
                + "\",\"kind\":\"topic\",\"count\":" + count + ",\"bytes\":" + bytes
                + ",\"dcount\":" + dcount + ",\"dbytes\":" + dbytes + "}";
    }

    private static BundledTreeIndex sample() {
        return BundledTreeIndex.builder()
                .add(row(ROOT, "", "Root", 15, 1500L, 0, 0L))
                .add(row(A, ROOT, "Alpha", 10, 1000L, 2, 50L))
                .add(row(B, ROOT, "Beta", 5, 500L, 1, 20L))
                .add(row(D, A, "Delta", 0, 0L, 0, 0L))
                .build();
    }

    @Test
    public void servesOneLevelWithChildAggregates() {
        BundledTreeIndex idx = sample();
        assertEquals(4, idx.size());

        TopicNode root = idx.fetchOneLevel(ROOT);
        assertNotNull(root);
        assertEquals(2, root.children().size());          // A and B, in file order
        assertEquals(1500L, root.subtreeBytes());
        assertTrue(root.hasSubtreeSize());

        TopicNode alpha = root.children().get(0);
        assertEquals(A, alpha.id());
        assertEquals(1000L, alpha.subtreeBytes());
        assertEquals(10, alpha.descendantCount());
        assertEquals(2, alpha.looseResourceCount());
        assertEquals(50L, alpha.looseResourceBytes());
        assertTrue("children come one level at a time", alpha.children().isEmpty());
    }

    @Test
    public void drillsIntoAChild() {
        TopicNode alpha = sample().fetchOneLevel(A);
        assertNotNull(alpha);
        assertEquals(1, alpha.children().size());
        assertEquals(D, alpha.children().get(0).id());
    }

    @Test
    public void unknownNodeIsNull() {
        assertNull(sample().fetchOneLevel(ABSENT));
        assertNull(sample().fetchOneLevel("not-a-node-id"));
        assertNull(sample().fetchOneLevel(null));
    }

    @Test
    public void skipsBlankMalformedAndDuplicateLines() {
        BundledTreeIndex.Builder b = BundledTreeIndex.builder()
                .add(row(ROOT, "", "Root", 1, 1L, 0, 0L))
                .add("")                       // blank: ignored, not counted
                .add("{not json")              // malformed: skipped
                .add(row(ROOT, "", "Dup", 9, 9L, 0, 0L)); // duplicate id: skipped
        BundledTreeIndex idx = b.build();
        assertEquals(1, idx.size());
        assertEquals(2, b.skipped());          // malformed + duplicate
        assertEquals(1L, idx.fetchOneLevel(ROOT).subtreeBytes()); // first row won
    }
}
