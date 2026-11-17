package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** K2GO-577: the sender's view of the receiver's progress. */
public class ShareProgressTest {

    private static ShareProgress.Item item(long moved, long total, boolean delivered) {
        return new ShareProgress.Item(moved, total, delivered);
    }

    @Test
    public void noReportYetIsOpenWithoutActivity() {
        ShareProgress p = new ShareProgress(ShareProgress.State.OPEN, Collections.emptyList());
        assertFalse(p.isEnded());
        assertFalse(p.hasActivity());
        assertEquals(0, p.percent(1000, 2));
    }

    @Test
    public void bytesDriveThePercentAgainstTheSelection() {
        ShareProgress p = new ShareProgress(ShareProgress.State.OPEN,
                Arrays.asList(item(300, 500, false)));
        assertTrue(p.hasActivity());
        assertEquals(300, p.movedBytes());
        assertEquals(30, p.percent(1000, 2));
    }

    @Test
    public void aDeliveredItemCountsWholeAndAllDeliveredIsHundred() {
        ShareProgress p = new ShareProgress(ShareProgress.State.OPEN,
                Arrays.asList(item(400, 500, true), item(100, 500, false)));
        assertEquals(600, p.movedBytes());
        assertEquals(60, p.percent(1000, 2));
        ShareProgress all = new ShareProgress(ShareProgress.State.OPEN,
                Arrays.asList(item(500, 500, true), item(500, 500, true)));
        assertEquals(100, all.percent(1000, 2));
    }

    @Test
    public void anHttpReceiverIsCountedByItems() {
        // Delivered over the HTTP fetch: no bytes reported (-1), only the delivered flag.
        ShareProgress p = new ShareProgress(ShareProgress.State.OPEN,
                Arrays.asList(item(-1, -1, true)));
        assertEquals(0, p.movedBytes());
        assertEquals(50, p.percent(1000, 2));
    }

    @Test
    public void neverClaimsHundredBeforeEveryItemIsDelivered() {
        ShareProgress p = new ShareProgress(ShareProgress.State.OPEN,
                Arrays.asList(item(1000, 1000, false)));
        assertEquals(99, p.percent(1000, 1));
    }

    @Test
    public void everyEndedStateIsEnded() {
        for (ShareProgress.State s : new ShareProgress.State[]{ShareProgress.State.COMPLETED,
                ShareProgress.State.CANCELLED, ShareProgress.State.EXPIRED, ShareProgress.State.GONE}) {
            assertTrue(new ShareProgress(s, null).isEnded());
        }
    }
}
