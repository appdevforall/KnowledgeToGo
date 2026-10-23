package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-logic tests for the delete queue state, its reclaimed total, and its derived progress. */
public class DeleteJobTest {

    @Test
    public void beginStartsAllPending() {
        DeleteJob job = new DeleteJob();
        job.begin(3);
        assertEquals(3, job.size());
        assertEquals(DeleteJob.PENDING, job.status(0));
        assertEquals(DeleteJob.PENDING, job.status(2));
        assertEquals(0, job.firstPending());
        assertEquals(-1, job.index());
    }

    @Test
    public void overallPercentIsSettledItemCount() {
        DeleteJob job = new DeleteJob();
        job.begin(4);
        job.setActive(0);
        assertEquals(0, job.overallPercent());   // active is not settled
        job.setDone(0, 10L);
        job.setFailed(1);
        assertEquals(50, job.overallPercent());   // 2 of 4 settled (done + failed)
    }

    @Test
    public void freedTotalSumsOnlySuccessfulDeletes() {
        DeleteJob job = new DeleteJob();
        job.begin(3);
        job.setDone(0, 100L);
        job.setFailed(1);
        job.setDone(2, 50L);
        assertEquals(150L, job.freedTotal());
    }

    @Test
    public void freedClampsNegativeToZero() {
        DeleteJob job = new DeleteJob();
        job.begin(1);
        job.setDone(0, -5L);   // the box should never report negative, but guard it
        assertEquals(0L, job.freed(0));
        assertEquals(0L, job.freedTotal());
    }

    @Test
    public void requeueFailedResetsOnlyFailedItemsAndTheirFreed() {
        DeleteJob job = new DeleteJob();
        job.begin(2);
        job.setDone(0, 100L);
        job.setActive(1);
        job.setFailed(1);
        assertTrue(job.hasFailed());
        assertEquals(1, job.requeueFailed());
        assertEquals(DeleteJob.DONE, job.status(0));      // the done item is untouched
        assertEquals(100L, job.freed(0));                 // and keeps its reclaimed bytes
        assertEquals(DeleteJob.PENDING, job.status(1));   // the failed item is re-queued
        assertEquals(0L, job.freed(1));
    }

    @Test
    public void settledAndAllDone() {
        DeleteJob job = new DeleteJob();
        job.begin(2);
        assertFalse(job.isSettled());
        job.setDone(0, 1L);
        job.setFailed(1);
        assertTrue(job.isSettled());
        assertFalse(job.allDone());
        job.requeueFailed();
        job.setDone(1, 1L);
        assertTrue(job.allDone());
    }

    @Test
    public void firstPendingReturnsMinusOneWhenDrained() {
        DeleteJob job = new DeleteJob();
        job.begin(1);
        job.setDone(0, 1L);
        assertEquals(-1, job.firstPending());
    }

    @Test
    public void emptyJobIsNeitherSettledNorDone() {
        DeleteJob job = new DeleteJob();
        job.begin(0);
        assertFalse(job.isSettled());
        assertFalse(job.allDone());
        assertEquals(0, job.overallPercent());
    }
}
