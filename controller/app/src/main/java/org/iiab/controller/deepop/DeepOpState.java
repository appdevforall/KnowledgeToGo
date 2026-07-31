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
package org.iiab.controller.deepop;

import org.iiab.controller.env.EnvironmentLock;

public final class DeepOpState {

    public enum Phase { IDLE, RUNNING, SUCCESS, FAILED }

    public final Phase phase;
    /** Which deep-env op this state belongs to (BACKUP / RESTORE / CLONE); null only when IDLE. */
    public final EnvironmentLock.Owner owner;
    public final int percent;      // 0..100, or -1 for indeterminate
    public final String step;      // resolved status label, e.g. "Backing up"
    public final String message;   // terminal message / error text
    public final long seq;         // assigned by the repository; identifies terminal events

    private DeepOpState(Phase phase, EnvironmentLock.Owner owner, int percent, String step, String message, long seq) {
        this.phase = phase;
        this.owner = owner;
        this.percent = percent;
        this.step = step != null ? step : "";
        this.message = message != null ? message : "";
        this.seq = seq;
    }

    public boolean isRunning() { return phase == Phase.RUNNING; }
    public boolean isTerminal() { return phase == Phase.SUCCESS || phase == Phase.FAILED; }

    /** Returns a copy with the given sequence number (the repository assigns it). */
    DeepOpState withSeq(long seq) { return new DeepOpState(phase, owner, percent, step, message, seq); }

    public static DeepOpState idle() { return new DeepOpState(Phase.IDLE, null, 0, "", "", 0L); }

    public static DeepOpState running(EnvironmentLock.Owner owner, String step, int percent) {
        return new DeepOpState(Phase.RUNNING, owner, percent, step, "", 0L);
    }

    public static DeepOpState success(EnvironmentLock.Owner owner, String message) {
        return new DeepOpState(Phase.SUCCESS, owner, 0, "", message, 0L);
    }

    public static DeepOpState failed(EnvironmentLock.Owner owner, String message) {
        return new DeepOpState(Phase.FAILED, owner, 0, "", message, 0L);
    }
}
