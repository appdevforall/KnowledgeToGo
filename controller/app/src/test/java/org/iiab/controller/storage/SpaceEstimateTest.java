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

import org.junit.Test;

public class SpaceEstimateTest {

    @Test
    public void appliesTheFactor() {
        assertEquals(2300L, SpaceEstimate.fromCompressed(1000L));
    }

    @Test
    public void isExactOnAwkwardRemainders() {
        // 2_834_585_013 * 23 / 10 = 6_519_545_529 (floor)
        assertEquals(6_519_545_529L, SpaceEstimate.fromCompressed(2_834_585_013L));
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
}
