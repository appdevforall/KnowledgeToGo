/*
 * ============================================================================
 * Name        : PlatformPresence.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5061. Whether a platform counts as present, from what the
 *               box said plus what we already know about it.
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

/**
 * Turns what the box said about a platform's endpoint into the boolean the dispatcher
 * takes.
 *
 * <p>The dispatcher's question is "is this platform on offer at all", and its answer for
 * "no" is terminal — the order is refused rather than queued. That is right for a Basic
 * tier that never carried Kolibri and wrong for every transient cause, so mapping an
 * unreachable endpoint to "not on offer" is the decision that matters. It used to be made
 * implicitly, by a probe returning {@code false} on any failure.
 *
 * <p>The vocabulary lives here rather than on the probe so the rule and the words it reads
 * are in one place, and so this stays testable without a network: the cases that matter
 * are the ones nobody can reproduce on demand — a box mid-restart, a platform too busy to
 * answer within a second and a half.
 */
public final class PlatformPresence {

    private PlatformPresence() {
    }

    /** What the box said about a platform's endpoint. */
    public enum Answered {
        /** It replied and the platform is serving. */
        YES,
        /** It replied that there is nothing there — a real 404. */
        NO,
        /** It did not usefully reply: timed out, refused, 5xx, threw. */
        NOTHING
    }

    /**
     * Whether to treat the platform as present.
     *
     * <p>Absent only when the box actually said so. Everything else is present, including
     * silence, and that asymmetry is the point: the two errors are not equal. Treating a
     * present platform as absent discards what the user asked for and tells them their box
     * does not have a feature it does have. Treating an absent one as present costs a
     * failed attempt with a real error message — and costs less now than it used to, since
     * the order is banked in a queue rather than acted on immediately.
     *
     * <p>The box being down needs no case of its own. A down box does not answer for its
     * platforms either, so it arrives here as {@code NOTHING} and is reported present —
     * which is what lets the dispatcher get as far as noticing the server is down and
     * saying so. That branch was unreachable while a silent endpoint meant absent.
     *
     * @param answered     what the endpoint said: yes, no, or nothing
     * @param workInFlight whether this platform is running a job for us right now
     */
    public static boolean resolve(Answered answered, boolean workInFlight) {
        // Proof beats a probe. If the platform is processing a job we submitted, it exists;
        // no timeout is evidence against something we are watching happen. This is the case
        // found on device: asking for a second courses download while the first was running
        // was refused as "not installed", because the import kept the platform busy enough
        // that a 1500 ms GET did not come back.
        if (workInFlight) {
            return true;
        }
        return answered != Answered.NO;
    }
}
