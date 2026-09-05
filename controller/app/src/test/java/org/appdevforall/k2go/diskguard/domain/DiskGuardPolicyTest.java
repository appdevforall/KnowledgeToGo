/*
 * ============================================================================
 * Name        : DiskGuardPolicyTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-386. JVM unit tests for the pure disk-guard rule.
 * ============================================================================
 */
package org.appdevforall.k2go.diskguard.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiskGuardPolicyTest {

    @Test
    public void nullOrNegativeFreeSpaceIsUnknown_soTheGuardDoesNotActOnABadRead() {
        assertEquals(DiskGuardPolicy.Level.UNKNOWN, DiskGuardPolicy.evaluate(null));
        assertEquals(DiskGuardPolicy.Level.UNKNOWN, DiskGuardPolicy.evaluate(-1L));
    }

    @Test
    public void belowTheFloorIsCritical() {
        assertEquals(DiskGuardPolicy.Level.CRITICAL,
                DiskGuardPolicy.evaluate(DiskGuardPolicy.CRITICAL_FLOOR_BYTES - 1));
        assertEquals(DiskGuardPolicy.Level.CRITICAL, DiskGuardPolicy.evaluate(0L));
    }

    @Test
    public void atOrAboveTheFloorIsOk() {
        assertEquals(DiskGuardPolicy.Level.OK,
                DiskGuardPolicy.evaluate(DiskGuardPolicy.CRITICAL_FLOOR_BYTES));
        assertEquals(DiskGuardPolicy.Level.OK, DiskGuardPolicy.evaluate(10L * 1024 * 1024 * 1024));
    }

    @Test
    public void criticalFloorIsBelowStorageGuardOpFloor_soLegitOpsNeverTripIt() {
        // Legit ops reserve >= 2 GiB (StorageGuard.DEFAULT_FLOOR_BYTES). Keeping the runtime critical
        // floor strictly below that means only a headroom-less runaway can cross it.
        assertTrue(DiskGuardPolicy.CRITICAL_FLOOR_BYTES < 2L * 1024 * 1024 * 1024);
    }
}
