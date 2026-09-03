package org.appdevforall.k2go.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the live-log auto-scroll "at bottom?" rule (K2GO-374). */
public class LogScrollTest {

    // A tall log: viewport 100px, content 1000px -> maxScroll 900px. Threshold 24px.
    private static final int VP = 100, CONTENT = 1000, MAX = CONTENT - VP, T = 24;

    @Test public void notLaidOutYetPinsToBottom() {
        assertTrue(LogScroll.isAtBottom(0, 0, CONTENT, T));
    }

    @Test public void contentThatFitsIsAlwaysAtBottom() {
        // content shorter than the viewport: nothing to scroll.
        assertTrue(LogScroll.isAtBottom(0, 100, 80, T));
        assertTrue(LogScroll.isAtBottom(0, 100, 100, T));
    }

    @Test public void exactBottomIsAtBottom() {
        assertTrue(LogScroll.isAtBottom(MAX, VP, CONTENT, T));
    }

    @Test public void withinThresholdIsAtBottom() {
        assertTrue(LogScroll.isAtBottom(MAX - T, VP, CONTENT, T));
    }

    @Test public void justBeyondThresholdIsNotAtBottom() {
        assertFalse(LogScroll.isAtBottom(MAX - T - 1, VP, CONTENT, T));
    }

    @Test public void scrolledUpToTopIsNotAtBottom() {
        assertFalse(LogScroll.isAtBottom(0, VP, CONTENT, T));
    }

    @Test public void negativeThresholdIsTreatedAsZero() {
        assertTrue(LogScroll.isAtBottom(MAX, VP, CONTENT, -5));
        assertFalse(LogScroll.isAtBottom(MAX - 1, VP, CONTENT, -5));
    }
}
