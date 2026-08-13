/*
 * ============================================================================
 * Name        : InstallState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable snapshot of the rootfs install pipeline's progress,
 *               published through InstallProgressRepository so the UI can observe
 *               it and re-bind after a recreation (ADFA-4474). PR2: the
 *               foreground InstallService drives every phase; terminal states
 *               (SUCCESS / FAILED) carry a monotonically increasing seq so the
 *               UI can fire one-shot effects (snackbars) exactly once.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

public final class InstallState {

    // ADFA-5118: VERIFYING is the archive-listing/safety pass that precedes EXTRACTING. It was
    // formerly folded into EXTRACTING as the indeterminate "reading" sub-phase; it is now its own
    // determinate phase so the unified bar can show real progress + an ETA for both passes.
    public enum Phase { IDLE, DOWNLOADING, VERIFYING, EXTRACTING, PROVISIONING, SUCCESS, FAILED }

    /** Which long-running operation this state belongs to (ADFA-4476). ADFA-5011 adds REBUILD
     *  (dash-node REST-core rebuild) so the progress screen can tell a rebuild apart from an install
     *  and stay put (blocking) until it reaches SUCCESS/FAILED. */
    public enum Op { INSTALL, RESET, REBUILD }

    public final Phase phase;
    public final Op op;
    // 0..100 for DOWNLOADING and for EXTRACTING while extracting; -1 means
    // indeterminate (the EXTRACTING "reading/listing" sub-phase, ADFA-4915).
    public final int percent;
    public final String speed;    // e.g. "12.3MiB", may be empty
    public final String message;  // resolved status text / current file line / error
    public final long seq;        // assigned by the repository; identifies terminal events

    /**
     * ADFA-4895: resolved "time left" text, or empty. A field of its own because DOWNLOADING has to
     * show a rate AND an estimate at once, so it cannot borrow the speed slot the way ADFA-5118's
     * verify/extract states do — those have no rate to display, so the slot was free there.
     *
     * <p>The 5118 states are left as they are. When their layout is next touched they should move
     * onto this field too, so "speed" means a rate everywhere and nothing has to be read twice to
     * find out what it is carrying.
     */
    public final String eta;

    private InstallState(Phase phase, Op op, int percent, String speed, String message, long seq) {
        this(phase, op, percent, speed, message, seq, "");
    }

    private InstallState(Phase phase, Op op, int percent, String speed, String message, long seq,
                         String eta) {
        this.eta = eta != null ? eta : "";
        this.phase = phase;
        this.op = op != null ? op : Op.INSTALL;
        this.percent = percent;
        this.speed = speed != null ? speed : "";
        this.message = message != null ? message : "";
        this.seq = seq;
    }

    public boolean isRunning() {
        return phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
                || phase == Phase.EXTRACTING || phase == Phase.PROVISIONING;
    }

    public boolean isTerminal() {
        return phase == Phase.SUCCESS || phase == Phase.FAILED;
    }

    /** Returns a copy with the given sequence number (the repository assigns it). */
    InstallState withSeq(long seq) {
        return new InstallState(phase, op, percent, speed, message, seq, eta);
    }

    /** Returns a copy tagged with the given operation (the repository stamps it). */
    InstallState withOp(Op op) {
        return new InstallState(phase, op, percent, speed, message, seq, eta);
    }

    public static InstallState idle() {
        return new InstallState(Phase.IDLE, Op.INSTALL, 0, "", "", 0L);
    }

    public static InstallState downloading(int percent, String speed) {
        return new InstallState(Phase.DOWNLOADING, Op.INSTALL, percent, speed, "", 0L);
    }

    /**
     * ADFA-4895: downloading with an estimate beside the rate. The screen shows all three —
     * "32%  ·  37MiB/s  ·  about 3 min left" — because on a slow link the percentage barely moves
     * and the rate alone does not say whether this finishes tonight.
     */
    public static InstallState downloading(int percent, String speed, String eta) {
        return new InstallState(Phase.DOWNLOADING, Op.INSTALL, percent, speed, "", 0L, eta);
    }

    public static InstallState extracting(String message) {
        return new InstallState(Phase.EXTRACTING, Op.INSTALL, 0, "", message, 0L);
    }

    /**
     * ADFA-4915: determinate extract progress. {@code percent} is pre-clamped by
     * ExtractProgress (0..99, then 100 on completion) or -1 for the indeterminate
     * "reading/listing" sub-phase; {@code message} is the current file line (may be empty).
     */
    public static InstallState extracting(int percent, String message) {
        return new InstallState(Phase.EXTRACTING, Op.INSTALL, percent, "", message, 0L);
    }

    /**
     * ADFA-5118: determinate verify (archive-listing) progress. {@code percent} is the unified
     * bar value [0,99] (or -1 when the archive size is unknown -> indeterminate fallback);
     * {@code message} is the current member line; {@code eta} is the resolved "time left" text
     * (may be empty) carried in the speed slot, as DOWNLOADING does for its rate.
     */
    public static InstallState verifying(int percent, String message, String eta) {
        return new InstallState(Phase.VERIFYING, Op.INSTALL, percent, eta, message, 0L);
    }

    /**
     * ADFA-5118: determinate extract progress with an ETA. Same as {@link #extracting(int,String)}
     * but carries the resolved "time left" text in the speed slot (DOWNLOADING uses speed for its
     * rate); the unified bar reuses this so both passes render identically.
     */
    public static InstallState extracting(int percent, String message, String eta) {
        return new InstallState(Phase.EXTRACTING, Op.INSTALL, percent, eta, message, 0L);
    }

    public static InstallState provisioning(String message) {
        return new InstallState(Phase.PROVISIONING, Op.INSTALL, 0, "", message, 0L);
    }

    public static InstallState success() {
        return new InstallState(Phase.SUCCESS, Op.INSTALL, 0, "", "", 0L);
    }

    public static InstallState failed(String message) {
        return new InstallState(Phase.FAILED, Op.INSTALL, 0, "", message, 0L);
    }
}
