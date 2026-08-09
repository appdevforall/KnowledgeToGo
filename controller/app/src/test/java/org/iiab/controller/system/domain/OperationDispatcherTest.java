package org.iiab.controller.system.domain;

import static org.iiab.controller.system.domain.OperationDispatcher.Dispatch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link OperationDispatcher} — the five answers, and chiefly the
 * three the app had no name for: deferral, a box that is merely switched off, and a
 * platform that was never installed. Pure JVM, no emulator.
 */
public class OperationDispatcherTest {

    private static final Operation SEED_COURSES = Operation.content("kolibri");
    private static final Operation INSTALL_COURSES = Operation.appInstall("kolibri");
    private static final Operation INSTALL_SYSTEM = Operation.system();

    /** The wizard: nothing on the device yet. */
    private static final SystemFacts FRESH = SystemFacts.none();
    /** Installed, whole, running — the ordinary case. */
    private static final SystemFacts RUNNING = SystemFacts.of(true, true, true);
    /** Installed and whole, but the box is switched off. */
    private static final SystemFacts OFF = SystemFacts.of(true, true, false);
    /** An install that was killed half-way: it will not boot. */
    private static final SystemFacts DAMAGED = SystemFacts.of(true, false, false);

    // ---- the ordinary answers ---------------------------------------------

    @Test
    public void contentOnARunningBoxJustRuns() {
        assertEquals(Dispatch.RUN_LIVE, OperationDispatcher.resolve(SEED_COURSES, RUNNING, true));
        assertTrue(OperationDispatcher.willRun(Dispatch.RUN_LIVE));
    }

    @Test
    public void installingAnAppTakesTheBoxDown() {
        assertEquals(Dispatch.RUN_STOPPED,
                OperationDispatcher.resolve(INSTALL_COURSES, RUNNING, true));
    }

    // ---- deferral: the answer the model was missing ------------------------

    @Test
    public void contentAskedForBeforeThereIsABoxIsDeferred() {
        // The wizard. Same operation, same mechanism — there is simply nothing to
        // run it against, so the order is taken and carried out after the install.
        Dispatch d = OperationDispatcher.resolve(SEED_COURSES, FRESH, true);
        assertEquals(Dispatch.DEFER, d);
        assertTrue(OperationDispatcher.isDeferred(d));
        assertFalse(OperationDispatcher.willRun(d));
    }

    @Test
    public void choosingModulesBeforeTheInstallIsDeferredToo() {
        // Picking modules pre-install is a choice about the install, not a job that
        // can run beside it.
        assertEquals(Dispatch.DEFER, OperationDispatcher.resolve(INSTALL_COURSES, FRESH, true));
    }

    @Test
    public void deferralDoesNotDependOnThePlatformBeingThere() {
        // Nothing is installed, so nothing can be said about the platform yet;
        // asking for it must not turn into "not on offer".
        assertEquals(Dispatch.DEFER, OperationDispatcher.resolve(SEED_COURSES, FRESH, false));
    }

    // ---- a box that is merely off ------------------------------------------

    @Test
    public void anIntactButStoppedBoxIsStartedRatherThanTreatedAsAbsent() {
        // Today every Get More probe fails in this state and an intact system looks
        // identical to one with no modules at all. It is neither: make sure the box
        // is up, then run.
        Dispatch d = OperationDispatcher.resolve(SEED_COURSES, OFF, true);
        assertEquals(Dispatch.ENSURE_SERVER_THEN_RUN_LIVE, d);
        assertTrue(OperationDispatcher.willRun(d));
        assertFalse(OperationDispatcher.isDeferred(d));
    }

    @Test
    public void notHavingAskedTheServerGetsTheSameAnswerAsBeingDown() {
        // "Nobody has polled yet" and "polled and found down" need the same action —
        // make sure it is up — so they share an answer. What they must NOT share is
        // RUN_LIVE, which would POST into a box that may not be listening.
        SystemFacts notAsked = SystemFacts.serverUnknown(true, true);
        assertFalse(notAsked.isServerStateKnown());
        assertEquals(Dispatch.ENSURE_SERVER_THEN_RUN_LIVE,
                OperationDispatcher.resolve(SEED_COURSES, notAsked, true));
    }

    @Test
    public void contentThatRunsStoppedNeedsNoServer() {
        // Legal on purpose: "stopped" does not always mean "stops the whole box" —
        // the Maps runrole coexists with a live server. So a stopped content op is
        // answered before the reachability question, not after it.
        Operation stoppedContent = Operation.of("maps",
                Operation.Kind.CONTENT, Operation.ExecutionClass.STOPPED);
        assertEquals(Dispatch.RUN_STOPPED,
                OperationDispatcher.resolve(stoppedContent, OFF, true));
        assertEquals(Dispatch.RUN_STOPPED,
                OperationDispatcher.resolve(stoppedContent, RUNNING, true));
        // It is still content, so an absent platform still refuses it.
        assertEquals(Dispatch.UNAVAILABLE,
                OperationDispatcher.resolve(stoppedContent, RUNNING, false));
    }

    // ---- a platform the tier never installed --------------------------------

    @Test
    public void contentForAnAbsentPlatformIsNotOnOffer() {
        // Basic carries neither Courses nor Books. This is not deferrable — there is
        // no future moment at which the queued order would become runnable.
        Dispatch d = OperationDispatcher.resolve(SEED_COURSES, RUNNING, false);
        assertEquals(Dispatch.UNAVAILABLE, d);
        assertFalse(OperationDispatcher.willRun(d));
        assertFalse(OperationDispatcher.isDeferred(d));
    }

    @Test
    public void installingThePlatformItselfDoesNotRequireThePlatform() {
        // Would be circular: the app install is what puts the platform there.
        assertEquals(Dispatch.RUN_STOPPED,
                OperationDispatcher.resolve(INSTALL_COURSES, RUNNING, false));
    }

    // ---- a replacement already agreed -----------------------------------------

    @Test
    public void contentIsDeferredWhileAReplacementIsPending() {
        // The reinstall wizard. Every readable fact says the box is fine — it is
        // installed, healthy and answering — and all of it is about to be wiped.
        // Reading only those facts downloaded live onto the doomed system and took
        // the user out of the wizard, so the reinstall never ran.
        SystemFacts doomed = RUNNING.withReplacementPending();
        assertTrue(doomed.isInstalled());
        assertTrue(doomed.isServerUp());
        assertEquals(Dispatch.DEFER, OperationDispatcher.resolve(SEED_COURSES, doomed, true));
    }

    @Test
    public void aPendingReplacementOutranksEveryOtherFactAboutTheBox() {
        // Whatever the box looks like, it is going away: the answer cannot depend on
        // the state of something with its days numbered.
        for (SystemFacts f : new SystemFacts[]{FRESH, RUNNING, OFF, DAMAGED}) {
            SystemFacts doomed = f.withReplacementPending();
            assertEquals("with " + doomed, Dispatch.DEFER,
                    OperationDispatcher.resolve(SEED_COURSES, doomed, true));
            assertEquals("with " + doomed, Dispatch.DEFER,
                    OperationDispatcher.resolve(INSTALL_COURSES, doomed, false));
        }
    }

    @Test
    public void theReplacementItselfStillRuns() {
        // The system operation IS the replacement, so it must survive its own flag.
        assertEquals(Dispatch.RUN_STOPPED,
                OperationDispatcher.resolve(INSTALL_SYSTEM, RUNNING.withReplacementPending(), true));
    }

    @Test
    public void pendingReplacementIsNotAFactAboutTheBox() {
        SystemFacts plain = SystemFacts.of(true, true, true);
        assertFalse(plain.isReplacementPending());
        SystemFacts doomed = plain.withReplacementPending();
        assertTrue(doomed.isReplacementPending());
        // Same box, different answer — so the two must not compare equal.
        assertNotEquals(plain, doomed);
        // Idempotent: asking twice is the same decision, not a second one.
        assertSame(doomed, doomed.withReplacementPending());
    }

    // ---- damaged -------------------------------------------------------------

    @Test
    public void nothingRunsOverADamagedSystemExceptTheRepair() {
        assertEquals(Dispatch.BLOCKED_DAMAGED,
                OperationDispatcher.resolve(SEED_COURSES, DAMAGED, true));
        assertEquals(Dispatch.BLOCKED_DAMAGED,
                OperationDispatcher.resolve(INSTALL_COURSES, DAMAGED, true));

        // The system operation IS the repair, so it has to survive the refusal.
        assertEquals(Dispatch.RUN_STOPPED,
                OperationDispatcher.resolve(INSTALL_SYSTEM, DAMAGED, true));
    }

    @Test
    public void aSystemOperationRunsWhateverTheStateOfTheBox() {
        for (SystemFacts f : new SystemFacts[]{FRESH, RUNNING, OFF, DAMAGED}) {
            assertEquals("with " + f, Dispatch.RUN_STOPPED,
                    OperationDispatcher.resolve(INSTALL_SYSTEM, f, false));
        }
    }

    // ---- fails closed --------------------------------------------------------

    @Test
    public void anythingItCannotPlaceIsRefusedRatherThanAttempted() {
        assertEquals(Dispatch.UNAVAILABLE, OperationDispatcher.resolve(null, RUNNING, true));
        assertEquals(Dispatch.UNAVAILABLE, OperationDispatcher.resolve(SEED_COURSES, null, true));
        assertFalse(OperationDispatcher.willRun(Dispatch.UNAVAILABLE));
        assertFalse(OperationDispatcher.willRun(Dispatch.BLOCKED_DAMAGED));
        assertFalse(OperationDispatcher.willRun(Dispatch.DEFER));
    }

    @Test
    public void anOperationMustDeclareBothAxes() {
        try {
            Operation.of("kolibri", null, Operation.ExecutionClass.LIVE);
            org.junit.Assert.fail("a kind is not optional");
        } catch (IllegalArgumentException expected) {
            // the point of the type is that nothing is left to be inferred later
        }
        try {
            Operation.of("kolibri", Operation.Kind.CONTENT, null);
            org.junit.Assert.fail("a class is not optional");
        } catch (IllegalArgumentException expected) {
            // same
        }
    }
}
