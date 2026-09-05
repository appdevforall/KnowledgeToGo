package org.appdevforall.k2go.diskguard.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FirehoseSignalTest {

    private static final long WINDOW = 25L * 60L * 1000L;

    @Test
    public void recurringAndRecent_isFresh() {
        // last truncation 5 min before the server's now.
        FirehoseSignal s = new FirehoseSignal(true, 3, 1_000_000L, 1_000_000L + 5L * 60L * 1000L);
        assertTrue(s.isFresh(WINDOW));
    }

    @Test
    public void notRecurring_isNotFresh() {
        FirehoseSignal s = new FirehoseSignal(false, 1, 1_000_000L, 1_000_000L + 60L * 1000L);
        assertFalse(s.isFresh(WINDOW));
    }

    @Test
    public void recurringButStale_isNotFresh() {
        // last truncation 40 min before now: beyond the window, the firehose likely resolved.
        FirehoseSignal s = new FirehoseSignal(true, 4, 1_000_000L, 1_000_000L + 40L * 60L * 1000L);
        assertFalse(s.isFresh(WINDOW));
    }

    @Test
    public void neverTruncated_isNotFresh() {
        FirehoseSignal s = new FirehoseSignal(true, 2, 0L, 5_000_000L);
        assertFalse(s.isFresh(WINDOW));
    }

    @Test
    public void negativeAge_isNotFresh() {
        // lastTruncatedAtMs after now (clock went backwards / bad read): reject rather than trust it.
        FirehoseSignal s = new FirehoseSignal(true, 2, 2_000_000L, 1_000_000L);
        assertFalse(s.isFresh(WINDOW));
    }
}
