/*
 * ============================================================================
 * Name        : InstallState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable snapshot of the rootfs install pipeline's progress,
 *               published through InstallProgressRepository so the UI can observe
 *               it and re-bind after a recreation (ADFA-4474). PR1 populates the
 *               DOWNLOADING phase; later phases are defined for the foreground
 *               service to use.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

public final class InstallState {

    public enum Phase { IDLE, DOWNLOADING, EXTRACTING, PROVISIONING, SUCCESS, FAILED }

    public final Phase phase;
    public final int percent;     // 0..100, meaningful for DOWNLOADING
    public final String speed;    // e.g. "12.3MiB", may be empty
    public final String message;  // optional human text (errors / phase note)

    private InstallState(Phase phase, int percent, String speed, String message) {
        this.phase = phase;
        this.percent = percent;
        this.speed = speed != null ? speed : "";
        this.message = message != null ? message : "";
    }

    public static InstallState idle() {
        return new InstallState(Phase.IDLE, 0, "", "");
    }

    public static InstallState downloading(int percent, String speed) {
        return new InstallState(Phase.DOWNLOADING, percent, speed, "");
    }

    public static InstallState phase(Phase phase) {
        return new InstallState(phase, 0, "", "");
    }

    public static InstallState phase(Phase phase, String message) {
        return new InstallState(phase, 0, "", message);
    }
}
