package org.appdevforall.k2go.setup.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-434: the run verdict rule (slice 1). Pure, so a plain JVM test. */
public class RunVerdictTest {

    private static final StreamState NONE = new StreamState(false, false, 0);

    /** A REST run that has fully drained with every stream settled and no proot/seed work. */
    private static RunSnapshot.Builder restDone() {
        return new RunSnapshot.Builder()
                .noRest(false).prootShown(false).moduleShown(false).drained(true)
                .zim(NONE).books(NONE).kolibri(NONE);
    }

    /** A proot-only run whose queue is terminal, no module server wait, no seed. */
    private static RunSnapshot.Builder prootOnly() {
        return new RunSnapshot.Builder()
                .noRest(true).prootShown(true).queueTerminalNotRunning(true);
    }

    @Test
    public void restNotDrainedIsWorking() {
        RunVerdict v = RunVerdict.of(restDone().drained(false).build());
        assertFalse(v.allComplete());
        assertEquals(RunVerdict.State.WORKING, v.state());
    }

    @Test
    public void restDrainedNoFailuresIsSuccess() {
        RunVerdict v = RunVerdict.of(restDone().build());
        assertTrue(v.allComplete());
        assertTrue(v.success());
        assertEquals(0, v.failedTotal());
    }

    @Test
    public void restIncompleteStreamBlocksCompletion() {
        RunVerdict v = RunVerdict.of(restDone()
                .zim(new StreamState(true, false, 0)).build());   // in session, not complete
        assertFalse(v.allComplete());
        assertEquals(RunVerdict.State.WORKING, v.state());
    }

    @Test
    public void restStreamFailureIsFailure() {
        RunVerdict v = RunVerdict.of(restDone()
                .zim(new StreamState(true, true, 2)).build());
        assertTrue(v.allComplete());
        assertTrue(v.failure());
        assertEquals(2, v.failedTotal());
    }

    @Test
    public void pendingSeedBlocksCompletion() {
        RunVerdict v = RunVerdict.of(restDone().seedPendingRun(true).build());
        assertFalse(v.allComplete());
    }

    @Test
    public void moduleServerNotSettledBlocksCompletion() {
        RunVerdict v = RunVerdict.of(restDone()
                .moduleShown(true).moduleServerSettled(false).build());
        assertFalse(v.allComplete());
    }

    @Test
    public void prootOnlyQueueNotTerminalIsWorking() {
        RunVerdict v = RunVerdict.of(prootOnly().queueTerminalNotRunning(false).build());
        assertFalse(v.allComplete());
    }

    @Test
    public void prootOnlyTerminalIsSuccess() {
        RunVerdict v = RunVerdict.of(prootOnly().build());
        assertTrue(v.success());
    }

    @Test
    public void prootOnlyModuleSettledIsSuccess() {
        RunVerdict v = RunVerdict.of(prootOnly()
                .moduleShown(true).moduleServerSettled(true).build());
        assertTrue(v.success());
    }

    @Test
    public void prootOnlyModuleNotSettledIsWorking() {
        RunVerdict v = RunVerdict.of(prootOnly()
                .moduleShown(true).moduleServerSettled(false).build());
        assertFalse(v.allComplete());
    }

    @Test
    public void slowServerRestartIsFailureEvenWithZeroFailedItems() {
        // The server never came up in time: complete, but a failure, not a silent success.
        RunVerdict v = RunVerdict.of(prootOnly()
                .moduleShown(true).moduleServerSettled(true).batchServerSlow(true).build());
        assertTrue(v.allComplete());
        assertEquals(0, v.failedTotal());
        assertTrue(v.failure());
        assertFalse(v.success());
    }

    @Test
    public void failedTotalSumsEverySource() {
        RunVerdict v = RunVerdict.of(restDone()
                .zim(new StreamState(true, true, 1))
                .books(new StreamState(true, true, 2))
                .kolibri(new StreamState(true, true, 0))
                .prootFailed(1)
                .forgejoSeedFailed(true)
                .build());
        assertEquals(1 + 2 + 0 + 1 + 1, v.failedTotal());
        assertTrue(v.failure());
    }

    @Test
    public void streamSettledForCompletion() {
        assertTrue(new StreamState(false, false, 0).settledForCompletion());   // no session
        assertFalse(new StreamState(true, false, 0).settledForCompletion());   // running
        assertTrue(new StreamState(true, true, 0).settledForCompletion());     // done
    }
}
