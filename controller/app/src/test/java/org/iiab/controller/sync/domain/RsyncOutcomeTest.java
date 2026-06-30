package org.iiab.controller.sync.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link RsyncOutcome} — exit-code classification (S14 step 1).
 * Pure JVM, no Android dependencies.
 */
public class RsyncOutcomeTest {

    @Test
    public void successCodes() {
        for (int c : new int[]{0, 23, 24}) {
            assertTrue("code " + c, RsyncOutcome.isSuccess(c));
            assertEquals(RsyncOutcome.Transfer.COMPLETE, RsyncOutcome.classifyTransfer(c));
        }
    }

    @Test
    public void hostDroppedCodes() {
        for (int c : new int[]{10, 12, 20}) {
            assertFalse("code " + c, RsyncOutcome.isSuccess(c));
            assertEquals(RsyncOutcome.Transfer.HOST_DROPPED, RsyncOutcome.classifyTransfer(c));
        }
    }

    @Test
    public void otherCodesAreError() {
        for (int c : new int[]{1, 5, 11, 30, 255}) {
            assertFalse(RsyncOutcome.isSuccess(c));
            assertEquals(RsyncOutcome.Transfer.ERROR, RsyncOutcome.classifyTransfer(c));
        }
    }

    @Test
    public void sigkillIsClassifiedAsKilled() {
        assertTrue(RsyncOutcome.wasKilledBySignal(137));   // 128 + SIGKILL(9)
        assertFalse(RsyncOutcome.wasKilledBySignal(143));  // SIGTERM is our own destroy()
        assertFalse(RsyncOutcome.wasKilledBySignal(1));
        assertFalse(RsyncOutcome.isSuccess(137));
        assertEquals(RsyncOutcome.Transfer.KILLED, RsyncOutcome.classifyTransfer(137));
    }
}
