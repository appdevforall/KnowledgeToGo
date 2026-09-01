/*
 * ============================================================================
 * Name        : PlatformPresence.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5061. Whether a platform counts as present, from the
 *               evidence we have about it.
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * Turns evidence about a platform into the {@code platformPresent} boolean
 * {@link OperationDispatcher#resolve} takes.
 *
 * <p>Lives beside the dispatcher rather than in a platform's own package. Decision 8 names
 * "platform present" as the fourth system fact, and a fact with one owner cannot have that
 * owner be Kolibri: books, kiwix and maps are asked the same question by the Home cards and
 * both hubs. It was written under {@code kolibri/domain} first, which would have read as
 * "Kolibri's answer" and been re-implemented rather than reused.
 *
 * <p>The dispatcher's answer for "not present" is terminal — the order is refused rather
 * than queued — which is right for a Basic tier that never carried the platform and wrong
 * for every transient cause. So the decision that matters is when an unreachable platform
 * may be called absent, and it used to be made implicitly, by a probe returning
 * {@code false} on any failure at all.
 *
 * <p>Pure, so the rule is testable: the cases that matter are the ones nobody can produce
 * on demand — a box mid-restart, a platform too busy to answer within a second and a half.
 */
public final class PlatformPresence {

    private PlatformPresence() {
    }

    /**
     * What the evidence says.
     *
     * <p>Deliberately not named after HTTP. Today the only source is a reachability request,
     * but the honest source is on disk — the installer's own flag in {@code local_vars.yml},
     * readable with no network — and when that lands it should be able to produce these
     * values without changing this type or its callers. Note it will need care: a missing
     * flag on disk is stronger evidence than a 404, so {@link #resolve} would want revisiting
     * at the same time.
     */
    public enum Evidence {
        /** The platform is there: it answered, or a record says it is installed. */
        PRESENT,
        /** It is not there: the box said so outright — a 404, or an absent install flag. */
        ABSENT,
        /** Nothing was established: timed out, refused, 5xx, threw. */
        NONE
    }

    /**
     * Whether to treat the platform as present.
     *
     * <p>Absent only when something actually said so. Everything else is present, including
     * silence, and that asymmetry is the point: the two errors do not cost the same. Calling
     * a present platform absent discards what the user asked for and tells them their box
     * lacks a feature it has. Calling an absent one present costs a failed attempt with a
     * real error — and costs less than it used to, since the order is banked in a queue
     * rather than acted on immediately.
     *
     * <p>The box being down needs no case of its own. A down box does not answer for its
     * platforms either, so it arrives as {@link Evidence#NONE} and is reported present —
     * which is what lets the dispatcher get as far as noticing the server is down and saying
     * so. That branch was unreachable while silence meant absent.
     *
     * <p>Note what is <em>not</em> here. A first version also took "is a job running for this
     * platform right now", on the grounds that a platform we are watching work cannot be
     * missing. True, and dropped anyway, for three reasons. It is an inference from the
     * user's own activity, which is the implicit derivation this ADR exists to remove. It
     * added nothing to the bug it was written for — a timeout is {@link Evidence#NONE} and
     * already reports present — buying only the power to override an outright 404, the
     * strongest evidence there is. And it had no lifecycle: the running flag lives in process
     * memory, so it vanishes on process death, which over a download measured in hours is the
     * ordinary case rather than an edge one. A proof that disappears exactly when it is
     * needed longest is not a proof.
     */
    public static boolean resolve(Evidence evidence) {
        return evidence != Evidence.ABSENT;
    }
}
