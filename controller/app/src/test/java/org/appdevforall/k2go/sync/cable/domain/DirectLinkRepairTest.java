package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.sync.cable.domain.DirectLinkRepair.Outcome;
import org.junit.Test;

public class DirectLinkRepairTest {

    @Test
    public void noAnswerFromAPeerIsRepairedOnce() {
        assertTrue(DirectLinkRepair.shouldRepair(Outcome.NO_ANSWER, false, false));
        assertFalse(DirectLinkRepair.shouldRepair(Outcome.NO_ANSWER, false, true));
    }

    @Test
    public void refusalProvesTheLinkWorks() {
        assertFalse(DirectLinkRepair.shouldRepair(Outcome.REFUSED, false, false));
    }

    @Test
    public void answerNeedsNoRepair() {
        assertFalse(DirectLinkRepair.shouldRepair(Outcome.ANSWERS, false, false));
    }

    @Test
    public void gatewayHasNoDirectLink() {
        assertFalse(DirectLinkRepair.shouldRepair(Outcome.NO_ANSWER, true, false));
    }

    @Test
    public void repairIsUsedUntilAnAnswer() {
        assertTrue(DirectLinkRepair.triedAfter(false, true, false));    // repaired, still no answer
        assertTrue(DirectLinkRepair.triedAfter(true, false, false));    // the run of "no" goes on
        assertFalse(DirectLinkRepair.triedAfter(true, false, true));    // an answer ends the run
        assertFalse(DirectLinkRepair.triedAfter(false, false, false));  // refused: no repair used
    }
}
