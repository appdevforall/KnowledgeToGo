package org.iiab.controller.kolibri.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.kolibri.domain.PlatformPresence.Answered;

import org.junit.Test;

/**
 * Unit tests for {@link PlatformPresence} — when an unreachable platform may be called
 * absent.
 *
 * <p>Worth testing because the interesting cases cannot be produced on demand on a device:
 * a box mid-restart, or a platform too busy importing to answer a GET within a second and
 * a half. Those are exactly the ones that used to be answered wrongly, and wrongly in the
 * expensive direction — the dispatcher's "absent" is terminal, so the user's order was
 * discarded rather than queued.
 */
public class PlatformPresenceTest {

    // ---- the one case that means absent ---------------------------------------

    @Test
    public void onlyAnActualNoMeansAbsent() {
        // A 404 is the box telling us there is nothing there. It is the sole evidence
        // strong enough to refuse the user terminally.
        assertFalse(PlatformPresence.resolve(Answered.NO, false));
    }

    @Test
    public void aYesMeansPresent() {
        assertTrue(PlatformPresence.resolve(Answered.YES, false));
    }

    // ---- silence is not absence ----------------------------------------------

    @Test
    public void silenceIsNotAbsence() {
        // The regression this class exists for. A timeout, a refused connection or a 502
        // while nginx waits for the platform behind it all arrive here as NOTHING, and all
        // of them used to be reported as "this platform is not installed".
        assertTrue(PlatformPresence.resolve(Answered.NOTHING, false));
    }

    @Test
    public void aDownBoxNeedsNoCaseOfItsOwn() {
        // A box that is off does not answer for its platforms either, so it arrives as
        // NOTHING. Reporting present is what lets the dispatcher get far enough to notice
        // the server is down and say so — the branch that was unreachable while silence
        // meant absent.
        assertTrue(PlatformPresence.resolve(Answered.NOTHING, false));
    }

    // ---- proof beats a probe -------------------------------------------------

    @Test
    public void workInFlightOverridesSilence() {
        // The device repro: a courses download running keeps the platform busy enough that
        // the probe times out, and the second order was refused as "not installed".
        assertTrue(PlatformPresence.resolve(Answered.NOTHING, true));
    }

    @Test
    public void workInFlightEvenOverridesAnOutrightNo() {
        // Deliberate, and the stronger claim of the two. If the box says 404 while we are
        // watching that same platform process our job, the 404 is the thing that is wrong —
        // a proxy still coming up, a path that moved. Refusing the user on it would be
        // acting on the less reliable of two contradictory observations.
        assertTrue(PlatformPresence.resolve(Answered.NO, true));
    }

    @Test
    public void noWorkInFlightLeavesTheAnswerToTheBox() {
        assertFalse(PlatformPresence.resolve(Answered.NO, false));
        assertTrue(PlatformPresence.resolve(Answered.YES, false));
    }
}
