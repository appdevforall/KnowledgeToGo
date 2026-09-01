/*
 * ============================================================================
 * Name        : SpaceEstimate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5105. Estimate the extracted (uncompressed) footprint of a
 *               compressed archive when no measured value exists. The rootfs now
 *               carries a measured size (ADFA-5110), so this only backs RESTORE:
 *               user backups are created on-device and don't record their
 *               original size, so all we have is the archive's compressed size
 *               (File.length() when the file is picked) times a factor. 2.2x sits
 *               above the ~1.63–1.80x a rootfs-shaped archive expands (the max
 *               measured across tiers), so the guard errs toward refusing; the
 *               StorageGuard margin adds more slack on top. A heuristic — a
 *               principled replacement (read the archive's real size) is a
 *               follow-up. Pure JVM, unit-testable, overflow-safe.
 * ============================================================================
 */
package org.appdevforall.k2go.storage;

public final class SpaceEstimate {

    private SpaceEstimate() {}

    // 2.2x. Kept as a fraction (not a double) so the math is exact and overflow-safe.
    public static final int UNCOMPRESSED_NUMERATOR = 22;
    public static final int UNCOMPRESSED_DENOMINATOR = 10;

    /** Conservative uncompressed-size estimate from a compressed size, in bytes. */
    public static long fromCompressed(long compressedBytes) {
        long c = Math.max(0L, compressedBytes);
        long est = (c / UNCOMPRESSED_DENOMINATOR) * UNCOMPRESSED_NUMERATOR
                 + ((c % UNCOMPRESSED_DENOMINATOR) * UNCOMPRESSED_NUMERATOR) / UNCOMPRESSED_DENOMINATOR;
        return est < c ? Long.MAX_VALUE : est;   // saturate on overflow (factor >= 1, so est >= c)
    }
}
