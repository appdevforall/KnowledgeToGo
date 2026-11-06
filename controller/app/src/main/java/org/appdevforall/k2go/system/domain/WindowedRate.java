/*
 * ============================================================================
 * Name        : WindowedRate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-555. A trailing-window transfer rate: bytes per second over
 *               the last window, not the cumulative average from the start.
 *               The content-exchange copy loops and the rootfs extract pass share
 *               this one implementation. Pure JVM (no android.*) => unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * The recent transfer rate, measured over a trailing time window.
 *
 * <p>{@link TransferRate} averages over the whole run, which is right when reports are coarse and a
 * stable number matters more than reacting to the last second. This is the opposite choice: a byte
 * stream that warms up slowly, or speeds up and slows down, needs a rate that follows the current
 * throughput. The cumulative average reads far too low after a slow start and then lags a recovery,
 * which is exactly what made the rootfs extract ETA read absurd minutes on a slow phone.
 *
 * <p>It anchors at the first sample, and re-anchors once the window is wide enough, reporting the
 * bytes moved since the last anchor divided by the elapsed time. Between re-anchors it holds the last
 * window's rate, so the shown number is steady rather than flickering on every tick. It is stateful,
 * so each transfer owns one instance; a single caller thread feeds it, so it needs no synchronization.
 *
 * <p>Pure and unit-tested: the caller supplies the clock reading, so a test drives it with a fake one.
 */
public final class WindowedRate {

    private final long windowMs;
    private long anchorMs;
    private long anchorBytes;
    private long rate = 0L;        // last closed window's bytes/sec; 0 means "nothing to report yet"
    private boolean started = false;

    /** @param windowMs the trailing window width; a value below 1 is treated as 1 ms. */
    public WindowedRate(long windowMs) {
        this.windowMs = windowMs < 1L ? 1L : windowMs;
    }

    /**
     * Feed the cumulative bytes transferred so far and the current monotonic clock reading (ms).
     * Returns the current rate in bytes per second: {@code 0} until the first window closes, then the
     * most recent window's throughput, held until the next window closes.
     *
     * <p>{@code 0} is the "say nothing" value, consistent with {@link TransferRate}: a caller omits
     * the rate and treats the ETA as not-yet-estimable rather than printing a stall.
     */
    public long sample(long cumulativeBytes, long nowMs) {
        if (!started) {
            anchorMs = nowMs;
            anchorBytes = cumulativeBytes;
            started = true;
            return rate;
        }
        long dt = nowMs - anchorMs;
        if (dt >= windowMs) {
            long db = cumulativeBytes - anchorBytes;
            rate = db > 0L ? db * 1000L / dt : 0L;
            anchorMs = nowMs;
            anchorBytes = cumulativeBytes;
        }
        return rate;
    }
}
