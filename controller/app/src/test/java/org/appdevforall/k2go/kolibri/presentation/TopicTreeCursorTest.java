package org.appdevforall.k2go.kolibri.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.kolibri.domain.TopicNode;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Unit tests for {@link TopicTreeCursor} — the path, the capped level cache and the
 * fetch ticket. These were the fragile part of browsing a tree over a slow
 * connection and the part a view-model test could not reach without a looper.
 * Pure JVM.
 */
public class TopicTreeCursorTest {

    private static final String CHANNEL = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String OTHER_CHANNEL = "0a1b2c3d4e5f60718293a4b5c6d7e8f9";
    private static final String ROOT = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String MATH = "6277aa0c44235435acdc8a9ed98f466b";
    private static final String FRACTIONS = "f9d3e0e46ea25789bbed672ff6a399ed";

    private static TopicNode node(String id, String title) {
        return TopicNode.of(id, title, TopicNode.KIND_TOPIC, false, 0L, 1,
                Arrays.asList(TopicNode.of(MATH, "leaf", "video", true, 10L, 0, null)));
    }

    private static TopicTreeCursor cursor() {
        TopicTreeCursor c = new TopicTreeCursor(32);
        c.reset(CHANNEL, ROOT, "Channel");
        return c;
    }

    @Test
    public void aFreshCursorIsNotOnAnything() {
        TopicTreeCursor c = new TopicTreeCursor(32);
        assertFalse(c.isStarted());
        assertFalse(c.isOn(CHANNEL));
        assertNull(c.currentId());
        assertEquals("", c.currentTitle());
    }

    @Test
    public void resetPutsTheCursorAtTheChannelRoot() {
        TopicTreeCursor c = cursor();
        assertTrue(c.isStarted());
        assertTrue(c.isOn(CHANNEL));
        assertFalse(c.isOn(OTHER_CHANNEL));
        assertEquals(ROOT, c.currentId());
        assertEquals("Channel", c.currentTitle());
        assertTrue(c.ancestorIds().isEmpty());
        assertEquals(Collections.singletonList("Channel"), c.trail());
    }

    @Test
    public void pushingBuildsTheAncestryAndTheTrail() {
        TopicTreeCursor c = cursor();
        c.push(MATH, "Mathematics");
        c.push(FRACTIONS, "Fractions");

        assertEquals(FRACTIONS, c.currentId());
        assertEquals("Fractions", c.currentTitle());
        // Outermost first, and the current node is NOT its own ancestor.
        assertEquals(Arrays.asList(ROOT, MATH), c.ancestorIds());
        assertEquals(Arrays.asList("Channel", "Mathematics", "Fractions"), c.trail());
    }

    @Test
    public void popStepsUpAndRefusesAtTheRoot() {
        TopicTreeCursor c = cursor();
        c.push(MATH, "Mathematics");

        assertTrue(c.pop());
        assertEquals(ROOT, c.currentId());

        // At the root there is nowhere to go: the caller uses this to leave instead.
        assertFalse(c.pop());
        assertEquals(ROOT, c.currentId());
        assertTrue(c.isStarted());
    }

    @Test
    public void aTicketStopsBeingCurrentOnceAnotherIsTaken() {
        TopicTreeCursor c = cursor();

        long first = c.begin();
        assertTrue(c.isCurrent(first));

        long second = c.begin();
        assertFalse(c.isCurrent(first));
        assertTrue(c.isCurrent(second));
    }

    @Test
    public void resetInvalidatesAnythingInFlightForTheOldChannel() {
        TopicTreeCursor c = cursor();
        long inFlight = c.begin();

        c.reset(OTHER_CHANNEL, MATH, "Other");

        // Otherwise a late answer for the abandoned tree repopulates its cache and
        // lands on the level the user is looking at now.
        assertFalse(c.isCurrent(inFlight));
    }

    @Test
    public void resetDropsThePathAndTheCache() {
        TopicTreeCursor c = cursor();
        c.push(MATH, "Mathematics");
        c.remember(ROOT, node(ROOT, "Channel"));
        c.remember(MATH, node(MATH, "Mathematics"));
        assertEquals(2, c.cachedLevels());

        c.reset(OTHER_CHANNEL, MATH, "Other");

        assertEquals(0, c.cachedLevels());
        assertEquals(MATH, c.currentId());
        assertEquals(Collections.singletonList("Other"), c.trail());
    }

    @Test
    public void aRememberedLevelComesBackWithoutAFetch() {
        TopicTreeCursor c = cursor();
        assertNull(c.cached(ROOT));

        c.remember(ROOT, node(ROOT, "Channel"));
        assertNotNull(c.cached(ROOT));

        c.forget(ROOT);
        assertNull(c.cached(ROOT));
    }

    @Test
    public void nullsAreIgnoredRatherThanCached() {
        TopicTreeCursor c = cursor();
        c.remember(null, node(ROOT, "Channel"));
        c.remember(ROOT, null);
        assertEquals(0, c.cachedLevels());
        assertNull(c.cached(null));
    }

    @Test
    public void theCacheIsCappedAndEvictsTheOldestFirst() {
        TopicTreeCursor c = new TopicTreeCursor(3);
        c.reset(CHANNEL, ROOT, "Channel");

        String a = "11111111111111111111111111111111";
        String b = "22222222222222222222222222222222";
        String d = "33333333333333333333333333333333";
        String e = "44444444444444444444444444444444";

        c.remember(a, node(a, "A"));
        c.remember(b, node(b, "B"));
        c.remember(d, node(d, "D"));
        assertEquals(3, c.cachedLevels());

        c.remember(e, node(e, "E"));

        // Capped, and the level read longest ago is the one that goes.
        assertEquals(3, c.cachedLevels());
        assertNull(c.cached(a));
        assertNotNull(c.cached(b));
        assertNotNull(c.cached(e));
    }

    @Test
    public void reRememberingAKnownLevelDoesNotEvictAnything() {
        TopicTreeCursor c = new TopicTreeCursor(2);
        c.reset(CHANNEL, ROOT, "Channel");

        String a = "11111111111111111111111111111111";
        String b = "22222222222222222222222222222222";
        c.remember(a, node(a, "A"));
        c.remember(b, node(b, "B"));

        // A retry re-reads a level already held; that is an update, not a new entry.
        c.remember(a, node(a, "A again"));

        assertEquals(2, c.cachedLevels());
        assertNotNull(c.cached(a));
        assertNotNull(c.cached(b));
    }

    @Test
    public void aCapOfZeroIsTreatedAsOneRatherThanBreaking() {
        TopicTreeCursor c = new TopicTreeCursor(0);
        c.reset(CHANNEL, ROOT, "Channel");
        c.remember(ROOT, node(ROOT, "Channel"));
        assertEquals(1, c.cachedLevels());
    }
}
