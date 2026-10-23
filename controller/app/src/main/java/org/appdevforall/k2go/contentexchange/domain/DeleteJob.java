package org.appdevforall.k2go.contentexchange.domain;

/**
 * The pure state of one delete run: a per-item status plus the bytes each delete reclaimed, and the
 * derived overall progress. The service mutates it as it drains the queue; the UI reads the derived
 * figures. No Android, no IO: unit-testable on a plain JVM.
 *
 * <p>Delete is atomic per item (the box removes the item or it fails), so there is no within-item
 * byte progress like the export copy has: overall progress is settled-item count, not a byte ratio.
 * The item is the unit of resume (ADR "Resilience"): a failed item is re-queued and re-done on its
 * own; the rest are untouched. The arrays are indexed by the original request order and set to one
 * length in {@link #begin}, so the UI can never read a length mismatch.
 */
public final class DeleteJob {

    public static final int PENDING = 0;
    public static final int ACTIVE = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;

    private int[] status = new int[0];
    /** Bytes reclaimed per item, as the box reported for each successful delete. */
    private long[] freed = new long[0];
    private int index = -1;

    /** Start a run over {@code count} items, all PENDING. */
    public void begin(int count) {
        int n = Math.max(0, count);
        status = new int[n];
        freed = new long[n];
        index = -1;
    }

    public int size() {
        return status.length;
    }

    public int status(int i) {
        return i >= 0 && i < status.length ? status[i] : FAILED;
    }

    public long freed(int i) {
        return i >= 0 && i < freed.length ? freed[i] : 0L;
    }

    /** The item currently being deleted, or -1 when none is active. */
    public int index() {
        return index;
    }

    /** The first item still waiting, or -1 when the queue is drained. */
    public int firstPending() {
        for (int i = 0; i < status.length; i++) {
            if (status[i] == PENDING) {
                return i;
            }
        }
        return -1;
    }

    public void setActive(int i) {
        if (i >= 0 && i < status.length) {
            status[i] = ACTIVE;
            index = i;
        }
    }

    /** Mark item {@code i} deleted and record the bytes the box reclaimed for it. */
    public void setDone(int i, long freedBytes) {
        if (i >= 0 && i < status.length) {
            status[i] = DONE;
            freed[i] = Math.max(0L, freedBytes);
        }
    }

    public void setFailed(int i) {
        if (i >= 0 && i < status.length) {
            status[i] = FAILED;
            freed[i] = 0L;
        }
    }

    /** Re-queue every failed item (reset its reclaimed figure) for a retry. Returns how many changed. */
    public int requeueFailed() {
        int changed = 0;
        for (int i = 0; i < status.length; i++) {
            if (status[i] == FAILED) {
                status[i] = PENDING;
                freed[i] = 0L;
                changed++;
            }
        }
        return changed;
    }

    public boolean hasFailed() {
        for (int st : status) {
            if (st == FAILED) {
                return true;
            }
        }
        return false;
    }

    public int doneCount() {
        return count(DONE);
    }

    public int failedCount() {
        return count(FAILED);
    }

    private int count(int target) {
        int c = 0;
        for (int st : status) {
            if (st == target) {
                c++;
            }
        }
        return c;
    }

    /** No item is still pending or active (the run has settled; some may have failed). */
    public boolean isSettled() {
        if (status.length == 0) {
            return false;
        }
        for (int st : status) {
            if (st == PENDING || st == ACTIVE) {
                return false;
            }
        }
        return true;
    }

    /** Every item was deleted successfully. */
    public boolean allDone() {
        return status.length > 0 && doneCount() == status.length;
    }

    /** Total bytes reclaimed across the items deleted so far. */
    public long freedTotal() {
        long t = 0L;
        for (long f : freed) {
            t += f;
        }
        return t;
    }

    /** Overall percent by settled-item count (delete has no within-item byte progress). */
    public int overallPercent() {
        int n = status.length;
        if (n == 0) {
            return 0;
        }
        int settled = doneCount() + failedCount();
        return (int) Math.min(100L, (long) settled * 100L / n);
    }
}
