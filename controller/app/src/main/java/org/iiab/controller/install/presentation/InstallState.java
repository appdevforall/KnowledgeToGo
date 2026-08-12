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

    public enum Phase { IDLE, DOWNLOADING, EXTRACTING, PROVISIONING, SUCCESS, FAILED }

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
     * ADFA-5119: monotonic stamp (elapsedRealtime) of the last time this run actually MOVED —
     * carried forward across posts that repeat the same position. Assigned by the repository; 0
     * means "never stamped" (an idle state, or a state built outside the repository).
     *
     * <p>{@code seq} cannot answer this. It counts posts, and a stalled download goes on posting
     * the same percent with a decaying speed for as long as the process lives, so seq keeps rising
     * while nothing happens. This is what a watchdog has to read.
     */
    public final long progressAtMs;

    private InstallState(Phase phase, Op op, int percent, String speed, String message, long seq,
                         long progressAtMs) {
        this.phase = phase;
        this.op = op != null ? op : Op.INSTALL;
        this.percent = percent;
        this.speed = speed != null ? speed : "";
        this.message = message != null ? message : "";
        this.seq = seq;
        this.progressAtMs = progressAtMs;
    }

    public boolean isRunning() {
        return phase == Phase.DOWNLOADING || phase == Phase.EXTRACTING || phase == Phase.PROVISIONING;
    }

    public boolean isTerminal() {
        return phase == Phase.SUCCESS || phase == Phase.FAILED;
    }

    /**
     * ADFA-5119: whether this state describes the same position in the work as {@code other}.
     *
     * <p>Deliberately blind to {@code speed}. A download that has stalled keeps reporting a
     * speed — a decaying average, then zero — while the percentage does not move, so counting a
     * speed change as progress would make a stall indistinguishable from a slow link forever.
     * {@code seq} is excluded for the same reason: it counts posts, not movement. What is left is
     * what the user would call getting somewhere.
     */
    boolean samePosition(InstallState other) {
        return other != null
                && phase == other.phase
                && op == other.op
                && percent == other.percent
                && message.equals(other.message);
    }

    /** Returns a copy with the given sequence number (the repository assigns it). */
    InstallState withSeq(long seq) {
        return new InstallState(phase, op, percent, speed, message, seq, progressAtMs);
    }

    /** Returns a copy tagged with the given operation (the repository stamps it). */
    InstallState withOp(Op op) {
        return new InstallState(phase, op, percent, speed, message, seq, progressAtMs);
    }

    /** ADFA-5119: returns a copy carrying the given "last moved" stamp (the repository assigns it). */
    InstallState withProgressAt(long progressAtMs) {
        return new InstallState(phase, op, percent, speed, message, seq, progressAtMs);
    }

    public static InstallState idle() {
        return new InstallState(Phase.IDLE, Op.INSTALL, 0, "", "", 0L, 0L);
    }

    public static InstallState downloading(int percent, String speed) {
        return new InstallState(Phase.DOWNLOADING, Op.INSTALL, percent, speed, "", 0L, 0L);
    }

    public static InstallState extracting(String message) {
        return new InstallState(Phase.EXTRACTING, Op.INSTALL, 0, "", message, 0L, 0L);
    }

    /**
     * ADFA-4915: determinate extract progress. {@code percent} is pre-clamped by
     * ExtractProgress (0..99, then 100 on completion) or -1 for the indeterminate
     * "reading/listing" sub-phase; {@code message} is the current file line (may be empty).
     */
    public static InstallState extracting(int percent, String message) {
        return new InstallState(Phase.EXTRACTING, Op.INSTALL, percent, "", message, 0L, 0L);
    }

    public static InstallState provisioning(String message) {
        return new InstallState(Phase.PROVISIONING, Op.INSTALL, 0, "", message, 0L, 0L);
    }

    public static InstallState success() {
        return new InstallState(Phase.SUCCESS, Op.INSTALL, 0, "", "", 0L, 0L);
    }

    public static InstallState failed(String message) {
        return new InstallState(Phase.FAILED, Op.INSTALL, 0, "", message, 0L, 0L);
    }

    /**
     * ADFA-5119: which kind of work a watchdog should budget for, or null when nothing is running.
     *
     * <p>The one place the pipeline's phases are translated into the domain's vocabulary, so the
     * rule about how long each may be silent stays in {@code InstallStaleness} and is not restated
     * next to a timer.
     */
    public org.iiab.controller.install.domain.InstallStaleness.Work work() {
        switch (phase) {
            case DOWNLOADING: return org.iiab.controller.install.domain.InstallStaleness.Work.DOWNLOAD;
            case EXTRACTING:  return org.iiab.controller.install.domain.InstallStaleness.Work.EXTRACT;
            case PROVISIONING: return org.iiab.controller.install.domain.InstallStaleness.Work.PROVISION;
            default: return null;
        }
    }
}
