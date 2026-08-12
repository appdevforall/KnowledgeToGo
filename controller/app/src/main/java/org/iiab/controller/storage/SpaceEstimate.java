/*
 * ============================================================================
 * Name        : SpaceEstimate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5105. Estimate the extracted (uncompressed) footprint of a
 *               compressed archive when a measured value isn't available yet —
 *               the interim "needed" for the destructive free-space guard until
 *               ADFA-5110 publishes the real rootfs size. One factor, decided
 *               once, deliberately generous so the guard errs toward refusing
 *               (a wipe that runs out of disk leaves an unbootable rootfs). Pure
 *               JVM, unit-testable. Overflow-safe.
 * ============================================================================
 */
package org.iiab.controller.storage;

public final class SpaceEstimate {

    private SpaceEstimate() {}

    // ~2.3x: a debian+iiab rootfs .tar.gz expands roughly 2–2.5x. Kept as a fraction (not a double)
    // so the math is exact and overflow-safe. Superseded by the measured size once ADFA-5110 lands.
    public static final int UNCOMPRESSED_NUMERATOR = 23;
    public static final int UNCOMPRESSED_DENOMINATOR = 10;

    /** Conservative uncompressed-size estimate from a compressed size, in bytes. */
    public static long fromCompressed(long compressedBytes) {
        long c = Math.max(0L, compressedBytes);
        long est = (c / UNCOMPRESSED_DENOMINATOR) * UNCOMPRESSED_NUMERATOR
                 + ((c % UNCOMPRESSED_DENOMINATOR) * UNCOMPRESSED_NUMERATOR) / UNCOMPRESSED_DENOMINATOR;
        return est < c ? Long.MAX_VALUE : est;   // saturate on overflow (factor >= 1, so est >= c)
    }
}
