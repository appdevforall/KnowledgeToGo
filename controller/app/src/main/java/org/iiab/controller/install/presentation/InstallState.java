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

    /** Which long-running operation this state belongs to (ADFA-4476). */
    public enum Op { INSTALL, RESET }

    public final Phase phase;
    public final Op op;
    public final int percent;     // 0..100, meaningful for DOWNLOADING (and EXTRACTING, ADFA-4915)
    public final long done;       // ADFA-4915: extracted members (EXTRACTING); 0 otherwise
    public final long total;      // ADFA-4915: total members (EXTRACTING); 0 otherwise
    public final String speed;    // e.g. "12.3MiB", may be empty
    public final String message;  // resolved status text / error to render
    public final long seq;        // assigned by the repository; identifies terminal events

    private InstallState(Phase phase, Op op, int percent, long done, long total, String speed, String message, long seq) {
        this.phase = phase;
        this.op = op != null ? op : Op.INSTALL;
        this.percent = percent;
        this.done = done;
        this.total = total;
        this.speed = speed != null ? speed : "";
        this.message = message != null ? message : "";
        this.seq = seq;
    }

    public boolean isRunning() {
        return phase == Phase.DOWNLOADING || phase == Phase.EXTRACTING || phase == Phase.PROVISIONING;
    }

    public boolean isTerminal() {
        return phase == Phase.SUCCESS || phase == Phase.FAILED;
    }

    /** Returns a copy with the given sequence number (the repository assigns it). */
    InstallState withSeq(long seq) {
        return new InstallState(phase, op, percent, done, total, speed, message, seq);
    }

    /** Returns a copy tagged with the given operation (the repository stamps it). */
    InstallState withOp(Op op) {
        return new InstallState(phase, op, percent, done, total, speed, message, seq);
    }

    public static InstallState idle() {
        return new InstallState(Phase.IDLE, Op.INSTALL, 0, 0L, 0L, "", "", 0L);
    }

    public static InstallState downloading(int percent, String speed) {
        return new InstallState(Phase.DOWNLOADING, Op.INSTALL, percent, 0L, 0L, speed, "", 0L);
    }

    public static InstallState extracting(String message) {
        return new InstallState(Phase.EXTRACTING, Op.INSTALL, 0, 0L, 0L, "", message, 0L);
    }

    /** ADFA-4915: determinate extract progress. percent is pre-clamped by ExtractProgress; done/total are members; message is the current file line (may be empty). */
    public static InstallState extracting(int percent, long done, long total, String message) {
        return new InstallState(Phase.EXTRACTING, Op.INSTALL, percent, done, total, "", message, 0L);
    }

    public static InstallState provisioning(String message) {
        return new InstallState(Phase.PROVISIONING, Op.INSTALL, 0, 0L, 0L, "", message, 0L);
    }

    public static InstallState success() {
        return new InstallState(Phase.SUCCESS, Op.INSTALL, 0, 0L, 0L, "", "", 0L);
    }

    public static InstallState failed(String message) {
        return new InstallState(Phase.FAILED, Op.INSTALL, 0, 0L, 0L, "", message, 0L);
    }
}
