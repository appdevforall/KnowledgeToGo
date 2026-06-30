package org.iiab.controller.sync.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for {@link TransferGuard} (S14 step 4 / EX6). Pure JVM. */
public class TransferGuardTest {

    @Test
    public void allowsReceiveWhenServerStopped() {
        TransferGuard.Decision d = TransferGuard.canReceive(false);
        assertTrue(d.allowed);
        assertEquals(TransferGuard.Blocker.NONE, d.blocker);
    }

    @Test
    public void blocksReceiveWhileServerRunning() {
        TransferGuard.Decision d = TransferGuard.canReceive(true);
        assertFalse(d.allowed);
        assertEquals(TransferGuard.Blocker.SERVER_RUNNING, d.blocker);
    }
}
