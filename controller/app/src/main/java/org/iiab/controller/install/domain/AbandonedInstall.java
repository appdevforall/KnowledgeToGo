/*
 * ============================================================================
 * Name        : AbandonedInstall.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5119. The rule for what a user-abandoned pipeline
 *               invalidates. Pure JVM, no Android.
 * ============================================================================
 */
package org.iiab.controller.install.domain;

/**
 * What is no longer true after the user abandons an operation.
 *
 * <p>Cancelling is not one event. {@code InstallService} runs four different kinds of work behind
 * the same {@code ACTION_CANCEL}, and only one of them leaves the device with nothing to boot. The
 * cleanup that is right for that one — forget the tier, forget the wishlists, send the user back to
 * choose again — is actively wrong for the others: applying it to a module install would take a
 * working system and route its owner into the first-run wizard.
 *
 * <p>So the question is asked once, here, rather than as a conjunction of three booleans inside a
 * 1,200-line service where the dangerous case is invisible.
 *
 * <p><b>Why a carried value and not a look at the disk.</b> {@code doCancel} runs later, on another
 * thread, after the pipeline has already changed the disk — a reinstall wipes the old rootfs before
 * it downloads, and an extract creates the rootfs directory long before it is usable. By then
 * "is there a system?" cannot be answered by looking. The pipeline knew when it decided; the
 * decision has to be carried rather than re-derived.
 */
public final class AbandonedInstall {

    /** The kinds of work {@code InstallService} runs, as far as abandoning them is concerned. */
    public enum Work {
        /**
         * Downloading and extracting the rootfs itself. Entered only once it is certain there is no
         * usable system — either none was there, or a reinstall has already wiped it.
         */
        ROOTFS_BUILD,
        /** Content added to a system that already exists (the companion-data / "Get more" path). */
        CONTENT,
        /** The per-module install queue. A system exists and keeps running. */
        MODULE_QUEUE,
        /**
         * The legacy Advanced screen's scratch reset.
         *
         * <p><b>Recorded gap, not an answer.</b> Abandoning a reset does leave the device without a
         * system, so by the rule below it belongs with {@link #ROOTFS_BUILD}. It is deliberately
         * left out because that route's own screens decide what happens next and changing them is
         * not in ADFA-5119's scope. Pinned by a test so it reads as a decision rather than an
         * oversight.
         */
        RESET
    }

    private AbandonedInstall() {
    }

    /**
     * Whether abandoning this work leaves the device with no system at all.
     *
     * <p>Everything the cancellation has to undo follows from this one answer: the partial download
     * and its control file, the half-written rootfs, the recorded tier, the pending wishlists, and
     * the setup-complete flag that decides whether the next launch opens the library or the wizard.
     * They are all statements about a system that will now never exist.
     */
    public static boolean leavesNoSystem(Work work) {
        return work == Work.ROOTFS_BUILD;
    }
}
