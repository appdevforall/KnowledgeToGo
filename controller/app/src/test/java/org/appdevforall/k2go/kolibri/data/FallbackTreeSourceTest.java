/*
 * ============================================================================
 * Name        : FallbackTreeSourceTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Unit tests for the local-first tree routing. Pure JVM (ADFA-5094).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.appdevforall.k2go.kolibri.domain.TopicNode;
import org.junit.Test;

/**
 * The routing that lets the box-served tree win and Studio cover the miss.
 *
 * <p>Three behaviours matter: a primary hit is returned without touching the
 * secondary, a primary miss falls through with the same node id, and two misses
 * are a miss — the null the {@code CatalogRepository} contract expects.
 */
public class FallbackTreeSourceTest {

    private static final String ID = "c150ea1d69495d37b5b0ac6f017e9bfb";

    /** Answers with whatever it was handed; records how it was used. */
    private static final class FakeSource implements TreeSource {
        final TopicNode answer;
        int calls;
        String asked;

        FakeSource(TopicNode answer) {
            this.answer = answer;
        }

        @Override
        public TopicNode fetchTree(String nodeId) {
            calls++;
            asked = nodeId;
            return answer;
        }
    }

    private static TopicNode node(String title) {
        return TopicNode.of(ID, title, "topic", false, 0L, 0, null);
    }

    @Test
    public void primaryHit_returnsPrimary_andSkipsSecondary() {
        TopicNode local = node("local");
        FakeSource primary = new FakeSource(local);
        FakeSource secondary = new FakeSource(node("studio"));

        TopicNode got = new FallbackTreeSource(primary, secondary).fetchTree(ID);

        assertSame("a primary hit must be returned as-is", local, got);
        assertEquals(1, primary.calls);
        assertEquals("secondary must not be consulted on a hit", 0, secondary.calls);
    }

    @Test
    public void primaryMiss_fallsToSecondary_withSameId() {
        FakeSource primary = new FakeSource(null);
        TopicNode studio = node("studio");
        FakeSource secondary = new FakeSource(studio);

        TopicNode got = new FallbackTreeSource(primary, secondary).fetchTree(ID);

        assertSame(studio, got);
        assertEquals(1, primary.calls);
        assertEquals(1, secondary.calls);
        assertEquals("the fallback must ask for the same node", ID, secondary.asked);
    }

    @Test
    public void bothMiss_returnsNull() {
        FallbackTreeSource fb = new FallbackTreeSource(new FakeSource(null), new FakeSource(null));
        assertNull(fb.fetchTree(ID));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullPrimary_rejected() {
        new FallbackTreeSource(null, new FakeSource(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullSecondary_rejected() {
        new FallbackTreeSource(new FakeSource(null), null);
    }
}
