package org.appdevforall.k2go.contentexchange.domain;

/**
 * The pure state of one export run: a per-item status plus how many bytes of each item have been
 * written, and the derived progress. The service mutates it as it drains the queue; the UI reads
 * the derived figures. No Android, no IO: unit-testable on a plain JVM.
 *
 * <p>The item is the unit of resume (ADR "Resilience"): a failed item is re-queued and re-done on
 * its own; the rest are untouched. The arrays are indexed by the original request order and set to
 * one length in {@link #begin}, so the UI can never read a length mismatch.
 */
public final class ExportJob {

    public static final int PENDING = 0;
    public static final int ACTIVE = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;

    private int[] status = new int[0];
    private long[] planned = new long[0];
    private long[] copied = new long[0];
    private int index = -1;

    /**
     * Start a run over {@code planned[i]} bytes per item (0 = unknown size). A negative planned
     * value marks an item the core could not plan: it starts {@link #FAILED}, not downloadable.
     */
    public void begin(long[] plannedBytes) {
        int n = plannedBytes != null ? plannedBytes.length : 0;
        status = new int[n];
        planned = new long[n];
        copied = new long[n];
        index = -1;
        for (int i = 0; i < n; i++) {
            if (plannedBytes[i] < 0) {
                status[i] = FAILED;
            } else {
                planned[i] = plannedBytes[i];
            }
        }
    }

    public int size() {
        return status.length;
    }

    public int status(int i) {
        return i >= 0 && i < status.length ? status[i] : FAILED;
    }

    public long planned(int i) {
        return i >= 0 && i < planned.length ? planned[i] : 0L;
    }

    public long copied(int i) {
        return i >= 0 && i < copied.length ? copied[i] : 0L;
    }

    /** The item currently being written, or -1 when none is active. */
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
            copied[i] = 0L;
            index = i;
        }
    }

    /** Record the absolute bytes written so far for item {@code i}. */
    public void setCopied(int i, long bytes) {
        if (i >= 0 && i < copied.length) {
            copied[i] = Math.max(0L, bytes);
        }
    }

    public void setDone(int i) {
        if (i >= 0 && i < status.length) {
            status[i] = DONE;
            // A chunked archive can run slightly over the planned size (tar headers); clamp so the
            // per-item and overall bars read a clean 100 percent.
            if (planned[i] > 0 && copied[i] < planned[i]) {
                copied[i] = planned[i];
            }
        }
    }

    public void setFailed(int i) {
        if (i >= 0 && i < status.length) {
            status[i] = FAILED;
        }
    }

    /** Re-queue every failed item (reset its progress) for a retry. Returns how many changed. */
    public int requeueFailed() {
        int changed = 0;
        for (int i = 0; i < status.length; i++) {
            if (status[i] == FAILED) {
                status[i] = PENDING;
                copied[i] = 0L;
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

    /** Every item finished successfully. */
    public boolean allDone() {
        return status.length > 0 && doneCount() == status.length;
    }

    public long plannedTotal() {
        long t = 0L;
        for (long p : planned) {
            t += p;
        }
        return t;
    }

    public long copiedTotal() {
        long t = 0L;
        for (int i = 0; i < copied.length; i++) {
            t += Math.min(copied[i], planned[i] > 0 ? planned[i] : copied[i]);
        }
        return t;
    }

    /** Overall percent, weighted by planned bytes; falls back to item count when sizes are unknown. */
    public int overallPercent() {
        long total = plannedTotal();
        if (total > 0) {
            long done = 0L;
            for (int i = 0; i < status.length; i++) {
                if (status[i] == DONE) {
                    done += planned[i];
                } else if (status[i] == ACTIVE) {
                    done += Math.min(copied[i], planned[i]);
                }
            }
            return (int) Math.min(100L, done * 100L / total);
        }
        int n = status.length;
        if (n == 0) {
            return 0;
        }
        int settled = doneCount() + failedCount();
        return (int) Math.min(100L, (long) settled * 100L / n);
    }

    /** Percent of the active item written so far (0 when its size is unknown). */
    public int currentItemPercent() {
        int i = index;
        if (i < 0 || i >= planned.length || planned[i] <= 0) {
            return 0;
        }
        return (int) Math.min(100L, copied[i] * 100L / planned[i]);
    }
}
