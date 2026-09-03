package org.appdevforall.k2go.system.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The whole truth table for the rule that replaces {@code setup_complete} (ADFA-5137). Pure JVM.
 *
 * <p>Written as eight explicit rows rather than as three assertions about "or", because the point of
 * the change is that every reachable combination now describes something real. The table is the
 * verification: if a row ever has to be argued about, the model is wrong again.
 */
public class SystemPresenceTest {

    private static boolean q(boolean rootfs, boolean installing, boolean deepOp) {
        return SystemPresence.hereOrOnTheWay(rootfs, installing, deepOp);
    }

    /** The one row that sends a user to the wizard — nothing there, nothing coming. */
    @Test
    public void nothingHereAndNothingComing() {
        assertFalse(q(false, false, false));
    }

    /** A finished system. The ordinary case, and the reason the flag existed at all. */
    @Test
    public void aRootfsOnDiskIsEnoughOnItsOwn() {
        assertTrue(q(true, false, false));
    }

    /**
     * Halfway through a first install: no usable rootfs yet, and sending this user back to the wizard
     * on every relaunch is the same dead end from the other side.
     */
    @Test
    public void anInstallInFlightCounts() {
        assertTrue(q(false, true, false));
    }

    /**
     * Receiving a clone, or restoring a backup. By definition there is no rootfs yet on a fresh
     * clone-receive, which is exactly why "is there a rootfs" alone would have been wrong.
     */
    @Test
    public void aDeepOperationInFlightCounts() {
        assertTrue(q(false, false, true));
    }

    /** The four remaining rows, all overlaps of the three above. */
    @Test
    public void theOverlapsAreAllTrue() {
        assertTrue(q(true, true, false));    // reinstall over an existing system
        assertTrue(q(true, false, true));    // backup or restore on a live system
        assertTrue(q(false, true, true));    // an install and a deep op both marked
        assertTrue(q(true, true, true));
    }

    /**
     * The property that makes the dead end unrepresentable: false has exactly one row. Under the old
     * flag, "no system" and "the wizard is done" were independent, so they could disagree; here they
     * are the same statement, and there is nowhere for a disagreement to live.
     */
    @Test
    public void exactlyOneOfTheEightRowsIsFalse() {
        int falses = 0;
        for (int i = 0; i < 8; i++) {
            if (!q((i & 1) != 0, (i & 2) != 0, (i & 4) != 0)) falses++;
        }
        assertEquals(1, falses);
    }
}
