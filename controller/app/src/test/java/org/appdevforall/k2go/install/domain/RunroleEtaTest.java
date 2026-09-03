package org.appdevforall.k2go.install.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for {@link RunroleEta} — seconds-remaining estimate (ADFA-5228). */
public class RunroleEtaTest {

    @Test
    public void zeroWhenNothingRemains() {
        assertEquals(0L, RunroleEta.secondsRemaining(0, -1L, 5, 50L));
    }

    @Test
    public void usesLearnedDurationsWhenKnown() {
        // 120s of remaining tasks known -> that's the estimate, ignoring the run rate.
        assertEquals(120L, RunroleEta.secondsRemaining(10, 120L, 3, 30L));
    }

    @Test
    public void fallsBackToObservedRateWhenNoHistory() {
        // 10 tasks in 60s -> 6s/task; 20 remain -> 120s.
        assertEquals(120L, RunroleEta.secondsRemaining(20, -1L, 10, 60L));
    }

    @Test
    public void unknownWhenNoHistoryAndNoMovementYet() {
        assertEquals(-1L, RunroleEta.secondsRemaining(20, -1L, 0, 0L));
        assertEquals(-1L, RunroleEta.secondsRemaining(20, -1L, 0, 5L));
    }

    @Test
    public void learnedZeroIsAValidEstimate() {
        // knownRemainingSeconds == 0 (all remaining tasks are instant) is still "known", not unknown.
        assertEquals(0L, RunroleEta.secondsRemaining(3, 0L, 1, 10L));
    }
}
