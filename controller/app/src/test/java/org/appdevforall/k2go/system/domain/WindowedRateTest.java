package org.appdevforall.k2go.system.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link WindowedRate} — the trailing-window transfer rate (K2GO-555). The tests drive
 * a fake monotonic clock so they assert exact rates without real time.
 */
public class WindowedRateTest {

    @Test
    public void reportsNothingBeforeTheFirstWindowCloses() {
        WindowedRate r = new WindowedRate(500L);
        assertEquals(0L, r.sample(0L, 0L));        // first sample anchors
        assertEquals(0L, r.sample(1_000L, 200L));  // window not wide enough yet
    }

    @Test
    public void reportsRecentThroughputOnceWindowCloses() {
        WindowedRate r = new WindowedRate(500L);
        r.sample(0L, 0L);
        // 500 KB over 500 ms = 1 MB/s.
        assertEquals(1_000_000L, r.sample(500_000L, 500L));
    }

    @Test
    public void holdsLastRateBetweenWindows() {
        WindowedRate r = new WindowedRate(500L);
        r.sample(0L, 0L);
        long first = r.sample(500_000L, 500L);     // window closes, rate = 1 MB/s
        assertEquals(first, r.sample(500_050L, 600L)); // 100 ms later: same held rate, window not closed
    }

    @Test
    public void followsASpeedChangeOnTheNextWindow() {
        WindowedRate r = new WindowedRate(500L);
        r.sample(0L, 0L);
        assertEquals(1_000_000L, r.sample(500_000L, 500L));   // fast window: 1 MB/s
        // Next window moves only 50 KB over 500 ms = 100 KB/s. The cumulative average would still read
        // ~0.5 MB/s here; the window reflects the slowdown.
        assertEquals(100_000L, r.sample(550_000L, 1_000L));
    }

    @Test
    public void reportsZeroForAStalledWindow() {
        WindowedRate r = new WindowedRate(500L);
        r.sample(1_000L, 0L);
        assertEquals(0L, r.sample(1_000L, 500L));  // no bytes moved across the window
    }

    @Test
    public void nonPositiveWindowDoesNotDivideByZero() {
        WindowedRate r = new WindowedRate(0L);     // clamped to 1 ms
        r.sample(0L, 0L);
        assertEquals(1_000_000L, r.sample(1_000L, 1L));   // 1000 B over 1 ms = 1 MB/s
    }
}
