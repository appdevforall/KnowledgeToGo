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
    /**
     * ADFA-5119 appends PAUSED, CANCELLED and SOFTFAILED rather than inserting them in reading
     * order, so no ordinal shifts. None existed before, which is why pausing and cancelling were the
     * same code path, a cancellation had to present itself as a failure, and a dropped transfer went
     * straight to a terminal that lifted the gate onto a library with no system.
     *
     * <p>The three name what the model was missing: who stopped it, and whether it can continue.
     * PAUSED — the user stopped it, it can continue. SOFTFAILED — something else stopped it, it can
     * continue. CANCELLED — the user gave it up, and there is nothing left to continue from.
     */
    public enum Phase { IDLE, DOWNLOADING, VERIFYING, EXTRACTING, PROVISIONING, SUCCESS, FAILED,
                        PAUSED, CANCELLED, SOFTFAILED }

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

    /**
     * In flight — there is work that has not finished, so nothing may treat this system as absent
     * or as a killed install.
     *
     * <p><b>PAUSED counts as running, and this is not a detail.</b> Twenty-seven call sites read
     * this, and one of them is the recovery predicate at {@code LibraryActivity:180}: marker set
     * AND not running is read as "a proot install was killed" and produces the damaged-system
     * dialog. If a deliberate pause left this false, pausing a download would offer to reinstall
     * the system. The gate's own {@code installing} flag reads it too, so a pause would also lift
     * the gate onto nothing.
     *
     * <p>What the UI wants is a different question — is it moving right now — and that is
     * {@link #isPaused()}, asked separately rather than folded in here.
     */
    public boolean isRunning() {
        return phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
                || phase == Phase.EXTRACTING || phase == Phase.PROVISIONING
                || phase == Phase.PAUSED || phase == Phase.SOFTFAILED;
    }

    /**
     * Finished, one way or another.
     *
     * <p>CANCELLED belongs here: the gate lifts on a terminal, and a cancellation is an ending the
     * user asked for. Leaving it out would hold the gate on a transfer that no longer exists.
     */
    public boolean isTerminal() {
        return phase == Phase.SUCCESS || phase == Phase.FAILED || phase == Phase.CANCELLED;
    }

    /**
     * ADFA-5119: stopped by the user, with everything transferred so far kept on disk.
     *
     * <p>The distinction from CANCELLED is the whole point of having both: a pause keeps the
     * partial file, its {@code .aria2} control file and the tier and wishlist decision, so
     * resuming costs nothing. A cancellation discards all four.
     */
    public boolean isPaused() {
        return phase == Phase.PAUSED;
    }

    /**
     * ADFA-5119: stopped on its own, and able to continue. A pause the user did not ask for.
     *
     * <p>Mechanically this is {@link #isPaused()}: the partial file, its control file and the tier
     * and wishlist decision are all still there, and continuing is the same call. It is a phase of
     * its own rather than a flag inside PAUSED because two things about it differ where the user can
     * see them — the line has to say what happened (they did not do this, so they need to know), and
     * the button says Retry rather than Resume. Folding it into PAUSED would also repeat the mistake
     * this ticket exists to undo: one name covering two events, the way a single boolean once covered
     * both pause and cancel.
     *
     * <p><b>Not terminal, and that is the whole point.</b> A dropped transfer used to become FAILED,
     * which lifts the gate onto a library with no system — the dead end this closes. The phase sits
     * beside FAILED in the enum so the pair reads as what it is: not final, and final.
     */
    public boolean isSoftFailed() {
        return phase == Phase.SOFTFAILED;
    }

    /** ADFA-5119: stopped and waiting for a tap, whoever stopped it. What the controls key off. */
    public boolean isHeld() {
        return isPaused() || isSoftFailed();
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

    /**
     * ADFA-5119: an automatic retry, which is still a download.
     *
     * <p>The phase is DOWNLOADING because that is what is happening — we are trying. Calling it
     * SOFTFAILED while an attempt is in flight would make the button say Retry over a transfer that
     * is already retrying, and would hand the user a decision the app has not finished taking.
     * SOFTFAILED begins when the attempts run out.
     *
     * <p>Carries no rate: the previous attempt died, so any figure would be from a connection that no
     * longer exists. {@code message} is the already-localized "Retry N of M" line, which the screen
     * shows on the detail row that DOWNLOADING otherwise leaves empty.
     */
    public static InstallState retrying(int percent, String message) {
        return new InstallState(Phase.DOWNLOADING, Op.INSTALL, percent, "", message, 0L, "");
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

    /**
     * ADFA-5119: paused by the user. Carries the percentage so the bar keeps its position, and
     * nothing else — there is no rate and no estimate while nothing is moving, and showing the
     * last ones would be stating a figure that is no longer true.
     */
    public static InstallState paused(int percent) {
        return new InstallState(Phase.PAUSED, Op.INSTALL, percent, "", "", 0L, "");
    }

    /**
     * ADFA-5119: cancelled by the user, after the residue is gone. Not a failure — a failure is
     * something that happened to the user, and this is something the user chose.
     */
    public static InstallState cancelled() {
        return new InstallState(Phase.CANCELLED, Op.INSTALL, 0, "", "", 0L, "");
    }

    /**
     * ADFA-5119: the transfer stopped on its own and can continue.
     *
     * <p>Carries the percentage for the same reason PAUSED does — the bytes are on disk, so the bar
     * keeps its position and Retry continues rather than starts over. Carries a reason as well,
     * because the user did not do this and a bar frozen with no explanation is the same dead end in
     * a quieter form.
     *
     * @param message an already-localized line naming what happened. Never {@code Aria2Exit.label()},
     *                which is deliberately English for logs; the presentation layer maps
     *                {@code Aria2Exit.Kind} to a resource.
     */
    public static InstallState softFailed(int percent, String message) {
        return new InstallState(Phase.SOFTFAILED, Op.INSTALL, percent, "", message, 0L, "");
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
