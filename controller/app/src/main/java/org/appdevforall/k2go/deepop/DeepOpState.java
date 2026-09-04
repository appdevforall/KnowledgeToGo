/*
 * ============================================================================
 * Name        : DeepOpState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4957. Immutable snapshot of a deep-environment operation (backup / restore /
 *               clone) in progress, published through DeepOpProgressRepository so the UI re-binds
 *               after a configuration-change recreation or backgrounding, and the foreground
 *               notification can route the user back to the live operation. Terminal states carry a
 *               monotonic seq so the UI fires one-shot effects exactly once. Mirrors InstallState.
 * ============================================================================
 */
package org.appdevforall.k2go.deepop;

import org.appdevforall.k2go.env.EnvironmentLock;

public final class DeepOpState {

    // K2GO-384: CANCELLED is a terminal distinct from FAILED — a user cancel in the pre-destructive zone
    // (copy/verify) is not an error. It lives on the state (not a fragment flag) so the "return to the
    // bifurcation, not a failure screen" decision survives a config change (mirrors InstallState.Phase).
    public enum Phase { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

    /**
     * K2GO-384: what cancelling the CURRENT restore pass would do -- the single source the UI reads to
     * pick the right dialog, owned by the service's real phase (never derived from the step text).
     *   NONE        - not cancellable here (e.g. backup, or no op).
     *   CANCELLABLE - a pre-destructive pass (copy / stopping / verify): tap Cancel HOLDS the run (the copy
     *                 pauses mid-loop; verify runs to completion and holds at the verify->extract boundary,
     *                 before any write) and asks -- Keep restoring (continue) or Cancel (abort, system
     *                 unchanged). Reversible.
     *   DESTRUCTIVE - the extract (`tar -x`, writing the rootfs): cancelling leaves the system damaged ->
     *                 recovery reinstalls; needs the strong red + acknowledgement confirm.
     */
    public enum CancelKind { NONE, CANCELLABLE, DESTRUCTIVE }

    public final Phase phase;
    /** Which deep-env op this state belongs to (BACKUP / RESTORE / CLONE); null only when IDLE. */
    public final EnvironmentLock.Owner owner;
    public final int percent;      // 0..100, or -1 for indeterminate
    public final long etaSeconds;  // K2GO-384: seconds left for the current pass, or -1 when unknown/hidden
    public final CancelKind cancelKind;  // K2GO-384: what a Cancel here means (drives the UI's dialog)
    public final String step;      // resolved status label, e.g. "Backing up"
    public final String message;   // terminal message / error text
    public final long seq;         // assigned by the repository; identifies terminal events

    private DeepOpState(Phase phase, EnvironmentLock.Owner owner, int percent, long etaSeconds, CancelKind cancelKind, String step, String message, long seq) {
        this.phase = phase;
        this.owner = owner;
        this.percent = percent;
        this.etaSeconds = etaSeconds;
        this.cancelKind = cancelKind != null ? cancelKind : CancelKind.NONE;
        this.step = step != null ? step : "";
        this.message = message != null ? message : "";
        this.seq = seq;
    }

    public boolean isRunning() { return phase == Phase.RUNNING; }
    public boolean isTerminal() { return phase == Phase.SUCCESS || phase == Phase.FAILED || phase == Phase.CANCELLED; }

    /** Returns a copy with the given sequence number (the repository assigns it). */
    DeepOpState withSeq(long seq) { return new DeepOpState(phase, owner, percent, etaSeconds, cancelKind, step, message, seq); }

    public static DeepOpState idle() { return new DeepOpState(Phase.IDLE, null, 0, -1L, CancelKind.NONE, "", "", 0L); }

    public static DeepOpState running(EnvironmentLock.Owner owner, String step, int percent, long etaSeconds, CancelKind cancelKind) {
        return new DeepOpState(Phase.RUNNING, owner, percent, etaSeconds, cancelKind, step, "", 0L);
    }

    public static DeepOpState success(EnvironmentLock.Owner owner, String message) {
        return new DeepOpState(Phase.SUCCESS, owner, 0, -1L, CancelKind.NONE, "", message, 0L);
    }

    public static DeepOpState failed(EnvironmentLock.Owner owner, String message) {
        return new DeepOpState(Phase.FAILED, owner, 0, -1L, CancelKind.NONE, "", message, 0L);
    }

    /** K2GO-384: a user cancel in the pre-destructive zone — terminal, but not a failure (no message; the
     *  screen returns to the bifurcation). */
    public static DeepOpState cancelled(EnvironmentLock.Owner owner) {
        return new DeepOpState(Phase.CANCELLED, owner, 0, -1L, CancelKind.NONE, "", "", 0L);
    }
}
