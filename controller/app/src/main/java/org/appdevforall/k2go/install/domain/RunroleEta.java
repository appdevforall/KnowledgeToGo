package org.appdevforall.k2go.install.domain;

/**
 * ADFA-5228: estimate the seconds remaining for a running runrole. Pure JVM, unit-tested.
 *
 * <p>Prefers learned per-task durations (the sum of the remaining tasks' known times); when there
 * is no history yet it falls back to the rate observed in THIS run (elapsed / tasks done, projected
 * over the tasks left). No warm-up — a runrole is not a download, so the estimate shows as soon as
 * there is anything to go on.
 */
public final class RunroleEta {

    private RunroleEta() {}

    /**
     * @param remainingTasks        tasks not yet reached (&gt;= 0)
     * @param knownRemainingSeconds sum of learned durations for the remaining tasks, or &lt; 0 if unknown
     * @param tasksDone             tasks reached so far
     * @param elapsedSeconds        seconds since the run started
     * @return estimated seconds remaining, or -1 when there is not enough information yet
     */
    public static long secondsRemaining(int remainingTasks, long knownRemainingSeconds,
                                        int tasksDone, long elapsedSeconds) {
        if (remainingTasks <= 0) return 0L;
        if (knownRemainingSeconds >= 0L) return knownRemainingSeconds;       // learned estimate
        if (tasksDone > 0 && elapsedSeconds > 0L) {                          // rate fallback (first run)
            return Math.round((double) elapsedSeconds / tasksDone * remainingTasks);
        }
        return -1L;
    }
}
