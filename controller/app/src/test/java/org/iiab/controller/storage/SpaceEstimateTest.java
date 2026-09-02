/*
 * ============================================================================
 * Name        : SpaceEstimateTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5105. Unit tests for the compressed -> uncompressed
 *               estimate: the 2.3x factor, zero/negative handling, and overflow
 *               saturation.
 * ============================================================================
 */
package org.iiab.controller.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpaceEstimateTest {

    @Test
    public void appliesTheFactor() {
        assertEquals(2200L, SpaceEstimate.fromCompressed(1000L));
    }

    @Test
    public void isExactOnAwkwardRemainders() {
        // 2_834_585_013 * 22 / 10 = 6_236_087_028 (floor)
        assertEquals(6_236_087_028L, SpaceEstimate.fromCompressed(2_834_585_013L));
    }

    @Test
    public void zeroAndNegativeAreZero() {
        assertEquals(0L, SpaceEstimate.fromCompressed(0L));
        assertEquals(0L, SpaceEstimate.fromCompressed(-5L));
    }

    @Test
    public void saturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, SpaceEstimate.fromCompressed(Long.MAX_VALUE));
    }

    // ---- K2GO-372: the copy and the tree it expands into share the disk ----

    @Test public void peakCoversTheCopyAndTheExpansion() {
        // 1 GB archive: 1 GB staged + 2.2 GB expanded must both fit at once.
        assertEquals(1_000_000_000L + SpaceEstimate.fromCompressed(1_000_000_000L),
                SpaceEstimate.peakForRestore(1_000_000_000L));
    }

    @Test public void peakIsAlwaysMoreThanTheExpansionAlone() {
        // The bug it closes: asking only about the expansion left the copy unaccounted for.
        for (long c : new long[]{1L, 1024L, 1_000_000L, 2_400_000_000L}) {
            assertTrue("peak must exceed the expansion for " + c,
                    SpaceEstimate.peakForRestore(c) > SpaceEstimate.fromCompressed(c));
        }
    }

    @Test public void peakIsZeroForNothingAndSaturatesInsteadOfWrapping() {
        assertEquals(0L, SpaceEstimate.peakForRestore(0L));
        assertEquals(0L, SpaceEstimate.peakForRestore(-5L));
        assertEquals(Long.MAX_VALUE, SpaceEstimate.peakForRestore(Long.MAX_VALUE));
    }
}
