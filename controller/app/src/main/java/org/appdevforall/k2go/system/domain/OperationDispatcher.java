/*
 * ============================================================================
 * Name        : OperationDispatcher.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The one place that answers "can this operation run, and how?".
 *               Pure JVM, no Android (ADFA-5061).
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * Decides what to do with an operation, from the facts rather than from context.
 *
 * <p>Modelled on {@code InterruptedInstallDetector}: a static verdict over plain
 * values, so it runs on a JVM with no emulator and the rule can be read in one
 * screen. The Android side gathers the facts and does what it is told; it does not
 * repeat the reasoning.
 *
 * <p><b>Why there are five answers and not two.</b> The two mechanisms — live over
 * REST, stopped under proot — were decided long ago. What was never named is that
 * an operation can be perfectly well-formed and still not be runnable <em>now</em>:
 *
 * <ul>
 *   <li><b>Deferral.</b> Seeding a Kolibri channel is a live operation on both
 *       doors — the same POST to the same REST core. In the wizard there is simply
 *       no box yet, or the one that is there is about to be replaced, so the order
 *       is written to a wishlist and drained after the install. Four content flows
 *       already do this, and before this class the distinction was carried by four
 *       booleans on an activity that did not survive being restored.</li>
 *   <li><b>The box is off.</b> Everything is installed and nothing is wrong; the
 *       server just is not running. That is not deferral — nothing needs to be
 *       queued — it is "start it first". Today this reads as absence: every Get
 *       More probe fails and an intact system looks like a system with no modules.</li>
 *   <li><b>The platform is absent.</b> The rootfs carries software, not content, and
 *       the tier decides which platforms come with it — Basic has neither Courses
 *       nor Books. Asking for Kolibri content there is not deferrable and not
 *       startable; it is simply not on offer.</li>
 *   <li><b>The system is damaged.</b> An install that was killed half-way leaves a
 *       rootfs that will not boot. Nothing may run against it except the repair.</li>
 * </ul>
 *
 * <p>The rule fails closed: anything it cannot place ends up refused rather than
 * attempted.
 */
public final class OperationDispatcher {

    /** What the caller should do with the operation it asked about. */
    public enum Dispatch {
        /** Run it now, over REST, with the box up. */
        RUN_LIVE,
        /**
         * Everything is in place, but the box is not known to be up: make sure it is
         * — probe, start it if it is down, wait — and then run live.
         *
         * <p>Deliberately the same answer for "off" and for "nobody has asked yet",
         * because the action is identical. It is <em>not</em> the same as
         * {@link #RUN_LIVE}: a caller that treats it as such will POST into a box
         * that is not listening.
         *
         * <p>Nothing implements this contract yet — {@code ServerController} can
         * start the environment and {@code RestReadiness} can probe it, but no one
         * composes the two. Until they do, a caller must not silently downgrade this
         * to {@code RUN_LIVE}.
         */
        ENSURE_SERVER_THEN_RUN_LIVE,
        /** Take the box down and run it under proot. */
        RUN_STOPPED,
        /** There is nothing to run it against yet: queue it and drain after the install. */
        DEFER,
        /** This platform is not on this system, so the operation is not on offer. */
        UNAVAILABLE,
        /** The system is half-installed and will not boot; only a repair may run. */
        BLOCKED_DAMAGED
    }

    private OperationDispatcher() {
    }

    /**
     * Resolves an operation whose platform needs no separate check — a system
     * operation, or one whose platform is known to be present.
     */
    public static Dispatch resolve(Operation op, SystemFacts facts) {
        return resolve(op, facts, true);
    }

    /**
     * @param op              what is being asked for; carries its own kind and class
     * @param facts           the state of the box
     * @param platformPresent whether this platform's app is installed. Only consulted
     *                        for {@link Operation.Kind#CONTENT}: the system and the
     *                        app installs are what put a platform there in the first
     *                        place, so requiring it of them would be circular.
     * @return never null
     */
    public static Dispatch resolve(Operation op, SystemFacts facts, boolean platformPresent) {
        if (op == null || facts == null) {
            return Dispatch.UNAVAILABLE;
        }

        // A system operation IS the repair — installing, restoring, resetting. It is
        // the one thing that must still run when everything else is refused, so it is
        // answered before the health check rather than after it.
        if (op.kind() == Operation.Kind.SYSTEM) {
            return Dispatch.RUN_STOPPED;
        }

        // A replacement has already been agreed and has not run yet: the user is in a
        // wizard that will install or wipe the box. Whatever is on it now is going
        // away, so nothing may be done TO it — the order is taken and carried out
        // against the system that is about to exist.
        //
        // This is checked before anything else about the box, because during a
        // reinstall every other fact says yes: the old system is installed, healthy
        // and answering right up to the moment it is wiped. Reading only those, the
        // picker downloaded live onto a system that was about to be destroyed and
        // took the user out of the wizard, so the reinstall never ran at all.
        if (facts.isReplacementPending()) {
            return Dispatch.DEFER;
        }

        // Installed but half-baked: it will not boot, and running anything over it
        // just produces a second failure to explain. Recovery first.
        if (facts.isInstalled() && !facts.isHealthy()) {
            return Dispatch.BLOCKED_DAMAGED;
        }

        // No box yet. Not an error: it is the wizard, and the honest answer is to
        // take the order now and carry it out once there is something to carry it
        // out against. Applies to an app install too — choosing modules pre-install
        // is a choice about the install, not a job that can run beside it.
        if (!facts.isInstalled()) {
            return Dispatch.DEFER;
        }

        if (op.kind() == Operation.Kind.APP_INSTALL) {
            return Dispatch.RUN_STOPPED;
        }

        // Content from here down. A platform that was never installed cannot take
        // content, and queueing it would be a promise nothing can keep.
        if (!platformPresent) {
            return Dispatch.UNAVAILABLE;
        }
        // A content operation that runs with the box stopped is legal — the model
        // has to allow it, because "stopped" does not always mean "stops the whole
        // box" (the Maps runrole coexists with a live server). It needs no server,
        // so it is answered before the reachability question.
        if (!op.isLive()) {
            return Dispatch.RUN_STOPPED;
        }
        if (!facts.isServerUp()) {
            // Covers both "known down" and "never asked": same action either way.
            return Dispatch.ENSURE_SERVER_THEN_RUN_LIVE;
        }
        return Dispatch.RUN_LIVE;
    }

    /**
     * Whether the answer means "write it down for later" rather than "do it".
     * The question the four {@code *Wizard} booleans were standing in for.
     */
    public static boolean isDeferred(Dispatch d) {
        return d == Dispatch.DEFER;
    }

    /**
     * Whether the answer means a stopped run may start <em>now</em>.
     *
     * <p>Strictly {@link Dispatch#RUN_STOPPED}, and the strictness is the point.
     * {@link #willRun} is the wrong question at this door: it also accepts
     * {@link Dispatch#ENSURE_SERVER_THEN_RUN_LIVE}, which is a live operation waiting on a box
     * that is not up, and a caller that treated the two alike would take the box down to run
     * something that needed it running. {@link Dispatch#DEFER} is a no too, but a kind one —
     * the order stays banked.
     */
    public static boolean mayRunStopped(Dispatch d) {
        return d == Dispatch.RUN_STOPPED;
    }

    /** Whether the answer will end up running, now or once the box is up. */
    public static boolean willRun(Dispatch d) {
        return d == Dispatch.RUN_LIVE
                || d == Dispatch.ENSURE_SERVER_THEN_RUN_LIVE
                || d == Dispatch.RUN_STOPPED;
    }
}
