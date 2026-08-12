/*
 * ============================================================================
 * Name        : StorageGuardTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5105. Unit tests for the single free-space rule: the
 *               hybrid margin max(floor, %), the FITS/DOES_NOT_FIT/UNKNOWN
 *               verdict (UNKNOWN on null/negative free space), the shortfall,
 *               the margin clamp, and overflow saturation.
 * ============================================================================
 */
package org.iiab.controller.storage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StorageGuardTest {

    private static final long GB = 1024L * 1024 * 1024;

    // ---- requiredBytes: hybrid margin max(floor, %) --------------------------

    @Test
    public void floorDominatesForSmallOperations() {
        // 1 GB op: 10% = 0.1 GB < 2 GB floor, so headroom is the floor.
        assertEquals(3L * GB, StorageGuard.requiredBytes(1L * GB));
    }

    @Test
    public void percentDominatesForLargeOperations() {
        // 50 GB op: 10% = 5 GB > 2 GB floor, so headroom is the fraction.
        assertEquals(55L * GB, StorageGuard.requiredBytes(50L * GB));
    }

    @Test
    public void zeroNeededStillRequiresTheFloor() {
        assertEquals(2L * GB, StorageGuard.requiredBytes(0L));
    }

    @Test
    public void negativeNeededIsTreatedAsZero() {
        assertEquals(2L * GB, StorageGuard.requiredBytes(-123L));
    }

    // ---- evaluate: three-state verdict --------------------------------------

    @Test
    public void fitsExactlyAtTheRequiredBoundary() {
        assertEquals(StorageGuard.Verdict.FITS, StorageGuard.evaluate(3L * GB, 1L * GB));
    }

    @Test
    public void doesNotFitOneByteBelowRequired() {
        assertEquals(StorageGuard.Verdict.DOES_NOT_FIT, StorageGuard.evaluate(3L * GB - 1L, 1L * GB));
    }

    @Test
    public void unknownWhenFreeSpaceIsNull() {
        assertEquals(StorageGuard.Verdict.UNKNOWN, StorageGuard.evaluate(null, 1L * GB));
    }

    @Test
    public void unknownWhenFreeSpaceIsNegative() {
        assertEquals(StorageGuard.Verdict.UNKNOWN, StorageGuard.evaluate(-1L, 1L * GB));
    }

    // ---- shortfall ----------------------------------------------------------

    @Test
    public void shortfallIsHowMuchMoreIsNeeded() {
        // needs 3 GB, has 2 GB -> 1 GB short.
        assertEquals(1L * GB, StorageGuard.shortfallBytes(2L * GB, 1L * GB));
    }

    @Test
    public void shortfallIsZeroWhenItFits() {
        assertEquals(0L, StorageGuard.shortfallBytes(3L * GB, 1L * GB));
    }

    @Test
    public void shortfallIsZeroWhenFreeSpaceIsUnknown() {
        assertEquals(0L, StorageGuard.shortfallBytes(null, 1L * GB));
    }

    // ---- policy overrides ---------------------------------------------------

    @Test
    public void aMarginBelowZeroIsClampedToTheFloorOnly() {
        // pct clamped to 0 -> headroom is exactly the floor.
        assertEquals(12L * GB, StorageGuard.requiredBytes(10L * GB, 2L * GB, -5));
    }

    @Test
    public void anExplicitPolicyIsHonoured() {
        // 20 GB op, 1 GB floor, 25% -> headroom = max(1, 5) GB = 5 GB.
        assertEquals(25L * GB, StorageGuard.requiredBytes(20L * GB, 1L * GB, 25));
    }

    // ---- overflow safety ----------------------------------------------------

    @Test
    public void requiredSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, StorageGuard.requiredBytes(Long.MAX_VALUE));
    }

    @Test
    public void anImpossiblyLargeOperationDoesNotFit() {
        assertEquals(StorageGuard.Verdict.DOES_NOT_FIT, StorageGuard.evaluate(100L * GB, Long.MAX_VALUE));
    }
}
