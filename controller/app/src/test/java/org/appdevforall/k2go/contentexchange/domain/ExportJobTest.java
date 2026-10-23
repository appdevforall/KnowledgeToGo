package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-logic tests for the export queue state and its derived progress. */
public class ExportJobTest {

    @Test
    public void beginMarksUnplannableItemsFailed() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{100L, -1L, 200L});
        assertEquals(ExportJob.PENDING, job.status(0));
        assertEquals(ExportJob.FAILED, job.status(1));   // negative planned = core could not plan it
        assertEquals(ExportJob.PENDING, job.status(2));
        // firstPending skips the pre-failed item.
        assertEquals(0, job.firstPending());
    }

    @Test
    public void overallPercentIsByteWeighted() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{900L, 100L});
        job.setDone(0);                 // 900 of 1000 bytes done
        assertEquals(90, job.overallPercent());
        job.setActive(1);
        job.setCopied(1, 50L);          // +50 of the 100-byte item
        assertEquals(95, job.overallPercent());
        // Overall is byte-weighted by copied bytes regardless of phase; the item bar is two-phase.
        job.setPhase(1, ExportJob.PHASE_WRITE);
        assertEquals(75, job.currentItemPercent());   // write half: 50 + 50% of 50
    }

    @Test
    public void currentItemBarIsTwoPhase() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{100L});
        job.setActive(0);
        // An active item starts in the prepare phase: the first half of the bar tracks stagedBytes.
        assertEquals(ExportJob.PHASE_PREPARE, job.phase(0));
        assertEquals(0, job.currentItemPercent());
        job.setStaged(0, 50L);
        assertEquals(25, job.currentItemPercent());    // half-staged = quarter of the whole bar
        job.setStaged(0, 100L);
        assertEquals(50, job.currentItemPercent());    // fully staged = the midpoint
        // The write phase fills the second half from copiedBytes.
        job.setPhase(0, ExportJob.PHASE_WRITE);
        assertEquals(50, job.currentItemPercent());    // nothing written yet
        job.setCopied(0, 50L);
        assertEquals(75, job.currentItemPercent());
        job.setCopied(0, 100L);
        assertEquals(100, job.currentItemPercent());
    }

    @Test
    public void currentItemBarWithUnknownSizeStillAdvancesByPhase() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{0L});     // size unknown
        job.setActive(0);
        job.setStaged(0, 123L);
        assertEquals(0, job.currentItemPercent());     // prepare half cannot show a fraction
        job.setPhase(0, ExportJob.PHASE_WRITE);
        assertEquals(50, job.currentItemPercent());    // but the bar still jumps to the midpoint
    }

    @Test
    public void setActiveResetsPhaseAndStaged() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{100L});
        job.setActive(0);
        job.setStaged(0, 40L);
        job.setPhase(0, ExportJob.PHASE_WRITE);
        job.setCopied(0, 30L);
        // A retry re-activates the item: it returns to the prepare phase with staged/copied cleared.
        job.setActive(0);
        assertEquals(ExportJob.PHASE_PREPARE, job.phase(0));
        assertEquals(0L, job.staged(0));
        assertEquals(0L, job.copied(0));
        assertEquals(0, job.currentItemPercent());
    }

    @Test
    public void overallPercentFallsBackToItemCountWhenSizesUnknown() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{0L, 0L, 0L, 0L});
        job.setDone(0);
        job.setDone(1);
        assertEquals(50, job.overallPercent());   // 2 of 4 settled
    }

    @Test
    public void doneClampsCopiedToPlanned() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{100L});
        job.setActive(0);
        job.setCopied(0, 120L);   // a tar can run over the planned size
        job.setDone(0);
        assertEquals(100, job.overallPercent());
        assertEquals(100L, job.copiedTotal());
    }

    @Test
    public void requeueFailedResetsOnlyFailedItems() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{10L, 10L});
        job.setDone(0);
        job.setActive(1);
        job.setCopied(1, 4L);
        job.setFailed(1);
        assertTrue(job.hasFailed());
        assertEquals(1, job.requeueFailed());
        assertEquals(ExportJob.DONE, job.status(0));      // the done item is untouched
        assertEquals(ExportJob.PENDING, job.status(1));   // the failed item is re-queued
        assertEquals(0L, job.copied(1));                  // and its progress reset
    }

    @Test
    public void settledAndAllDone() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{10L, 10L});
        assertFalse(job.isSettled());
        job.setDone(0);
        job.setFailed(1);
        assertTrue(job.isSettled());
        assertFalse(job.allDone());
        job.requeueFailed();
        job.setDone(1);
        assertTrue(job.allDone());
    }

    @Test
    public void firstPendingReturnsMinusOneWhenDrained() {
        ExportJob job = new ExportJob();
        job.begin(new long[]{10L});
        job.setDone(0);
        assertEquals(-1, job.firstPending());
    }
}
