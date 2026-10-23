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
        assertEquals(50, job.currentItemPercent());
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
