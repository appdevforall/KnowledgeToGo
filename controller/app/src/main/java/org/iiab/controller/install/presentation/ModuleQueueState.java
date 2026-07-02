/*
 * ============================================================================
 * Name        : ModuleQueueState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable snapshot of the per-module install queue (ADFA-4476
 *               slice 3). The InstallService owns the queue loop and publishes
 *               this to ModuleQueueRepository; the module grid observes it, so
 *               the "which module is installing right now" truth comes from the
 *               app (the engine), never from the half-written local_vars.yml.
 *
 *               Kept separate from InstallState (the rootfs install / reset
 *               pipeline) because the queue has a different shape: a current
 *               module, how many remain, and which ones failed. The two are
 *               mutually exclusive at runtime, so they never overlap.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import java.util.Collections;
import java.util.List;

public final class ModuleQueueState {

    public enum Phase { IDLE, RUNNING, DONE }

    public final Phase phase;
    /** The module (yamlBaseKey) whose runrole is executing right now, or null. */
    public final String currentModule;
    /** Modules still queued after the current one (does not include currentModule). */
    public final int remaining;
    /** Modules whose runrole failed in this batch. Only meaningful on DONE. */
    public final List<String> failedModules;
    /** Monotonic sequence for one-shot effects (finish/fail snackbars exactly once). */
    public final long seq;

    private ModuleQueueState(Phase phase, String currentModule, int remaining, List<String> failedModules, long seq) {
        this.phase = phase;
        this.currentModule = currentModule;
        this.remaining = Math.max(0, remaining);
        this.failedModules = failedModules != null
                ? Collections.unmodifiableList(failedModules)
                : Collections.emptyList();
        this.seq = seq;
    }

    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /** True when {@code moduleKey} is the module currently being installed. */
    public boolean isInstalling(String moduleKey) {
        return phase == Phase.RUNNING && currentModule != null && currentModule.equals(moduleKey);
    }

    public static ModuleQueueState idle() {
        return new ModuleQueueState(Phase.IDLE, null, 0, null, 0L);
    }

    public static ModuleQueueState running(String currentModule, int remaining) {
        return new ModuleQueueState(Phase.RUNNING, currentModule, remaining, null, 0L);
    }

    public static ModuleQueueState done(List<String> failedModules) {
        return new ModuleQueueState(Phase.DONE, null, 0, failedModules, 0L);
    }

    ModuleQueueState withSeq(long seq) {
        return new ModuleQueueState(phase, currentModule, remaining, failedModules, seq);
    }
}
