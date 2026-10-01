/*
 * ============================================================================
 * Name        : DownloadRetryPolicy.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-436 (slice 1). Pure rule for what to do after a download stop: how many times to
 *               retry in the open, with what backoff, before holding for the user, and when real
 *               progress forgives the spent attempts. This is the policy Aria2Exit deliberately does
 *               NOT own ("describes, does not decide"); it lives here so InstallService.softFail can
 *               ask instead of inlining the decision. No Android, no I/O: unit-testable on a plain JVM.
 *               Slice 1 of controller/docs/ADR-installservice-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.download.domain;

public final class DownloadRetryPolicy {

    /** Self-retries we make in the open before holding the decision for the user. */
    public static final int MAX_ATTEMPTS = 3;

    /**
     * Backoff before each self-retry, indexed by (attempt - 1) and clamped to the last entry.
     * Escalating, not back-to-back (ADFA-5119): with no network an attempt fails at once, so a zero or
     * fixed gap flashed all three past faster than the label could be read; a growing gap is both a
     * better chance the network returned and a readable count before the decision passes to the user.
     */
    private static final long[] DELAYS_MS = {3_000L, 6_000L, 9_000L};

    /**
     * Progress past the last attempt floor that forgives the spent attempts. A megabyte is the bar:
     * large enough that a stall flicker of a few bytes does not count, small enough that anything a
     * bad link genuinely delivers resets the budget (ADFA-5119).
     */
    public static final long PROGRESS_FORGIVE_BYTES = 1L << 20; // 1 MiB

    public enum Action { RETRY, HOLD }

    public static final class Decision {
        public final Action action;
        /** 1-based attempt number for a RETRY; 0 for HOLD. */
        public final int attempt;
        /** Backoff before this RETRY in milliseconds; 0 for HOLD. */
        public final long delayMs;

        private Decision(Action action, int attempt, long delayMs) {
            this.action = action;
            this.attempt = attempt;
            this.delayMs = delayMs;
        }
    }

    private DownloadRetryPolicy() {
    }

    /**
     * A stop we can continue from, because aria2 kept its control file: TRANSIENT, STALLED, or UNKNOWN.
     * UNKNOWN is included on purpose (aria2's own code 1 covers transient conditions as often as real
     * ones, and offering a retry that fails again costs one tap, while treating a recoverable stop as
     * permanent costs the whole download). PERMANENT and SUCCESS are not retryable here.
     */
    public static boolean isRetryable(Aria2Exit.Kind kind) {
        return kind == Aria2Exit.Kind.TRANSIENT
                || kind == Aria2Exit.Kind.STALLED
                || kind == Aria2Exit.Kind.UNKNOWN;
    }

    /**
     * After a retryable stop with {@code attemptsSoFar} self-retries already spent, decide whether to
     * retry again (with its backoff) or hold for the user. The caller gates on {@link #isRetryable}
     * first, so the kind does not re-enter the decision here.
     */
    public static Decision decide(int attemptsSoFar) {
        int spent = Math.max(0, attemptsSoFar);   // a public rule: never index below 0 on a bad count
        if (spent < MAX_ATTEMPTS) {
            int attempt = spent + 1;
            long delay = DELAYS_MS[Math.min(attempt - 1, DELAYS_MS.length - 1)];
            return new Decision(Action.RETRY, attempt, delay);
        }
        return new Decision(Action.HOLD, 0, 0L);
    }

    /**
     * Whether the bytes on disk have advanced far enough past the last attempt floor to forgive the
     * spent attempts (reset the budget to zero). Comparing against where the last attempt stopped,
     * not a global ceiling, is what lets a flapping link with real progress behind it keep going while
     * a link delivering nothing still terminates.
     */
    public static boolean progressForgivesAttempts(long completedBytes, long floorBytes) {
        return completedBytes > 0 && completedBytes >= floorBytes + PROGRESS_FORGIVE_BYTES;
    }
}
