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
package org.appdevforall.k2go.install.presentation;

import java.util.Collections;
import java.util.List;

public final class ModuleQueueState {

    public enum Phase { IDLE, RUNNING, DONE }

    /** Sentinel for "no determinate progress" — the current module runs indeterminate. */
    public static final int INDETERMINATE = -1;
    /** ADFA-5228: sentinel for "no ETA yet". */
    public static final long ETA_UNKNOWN = -1L;

    public final Phase phase;
    /** The module (yamlBaseKey) whose runrole is executing right now, or null. */
    public final String currentModule;
    /** Modules still queued after the current one (does not include currentModule). */
    public final int remaining;
    /**
     * ADFA-5228: determinate progress of the current module's runrole, 0..100, or
     * {@link #INDETERMINATE} (-1) when there is no task table for it (fall back to a spinner).
     */
    public final int percent;
    /** ADFA-5228: estimated seconds remaining for the current module, or {@link #ETA_UNKNOWN}. */
    public final long etaSeconds;
    /** Modules whose runrole failed in this batch. Only meaningful on DONE. */
    public final List<String> failedModules;
    /** Monotonic sequence for one-shot effects (finish/fail snackbars exactly once). */
    public final long seq;

    private ModuleQueueState(Phase phase, String currentModule, int remaining, int percent,
                             long etaSeconds, List<String> failedModules, long seq) {
        this.phase = phase;
        this.currentModule = currentModule;
        this.remaining = Math.max(0, remaining);
        this.percent = percent;
        this.etaSeconds = etaSeconds;
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

    /**
     * ADFA-4898: the queue finished with at least one module's runrole failed. An explicit signal so a
     * failed batch is not read as a clean success — kept on the DONE terminal (the failedModules list is
     * already published there) rather than a separate phase, so every existing "queue finished" consumer
     * keeps working and only the surfaces that care read this.
     */
    public boolean hasFailures() {
        return phase == Phase.DONE && !failedModules.isEmpty();
    }

    /** True when {@code moduleKey} failed in the batch that just finished. */
    public boolean didFail(String moduleKey) {
        return phase == Phase.DONE && failedModules.contains(moduleKey);
    }

    public static ModuleQueueState idle() {
        return new ModuleQueueState(Phase.IDLE, null, 0, INDETERMINATE, ETA_UNKNOWN, null, 0L);
    }

    /** Running with no determinate progress (indeterminate spinner). */
    public static ModuleQueueState running(String currentModule, int remaining) {
        return running(currentModule, remaining, INDETERMINATE, ETA_UNKNOWN);
    }

    /** ADFA-5228: running with a determinate percent (0..100), no ETA. */
    public static ModuleQueueState running(String currentModule, int remaining, int percent) {
        return running(currentModule, remaining, percent, ETA_UNKNOWN);
    }

    /** ADFA-5228: running with a determinate percent and an estimated seconds-remaining. */
    public static ModuleQueueState running(String currentModule, int remaining, int percent, long etaSeconds) {
        return new ModuleQueueState(Phase.RUNNING, currentModule, remaining, percent, etaSeconds, null, 0L);
    }

    public static ModuleQueueState done(List<String> failedModules) {
        return new ModuleQueueState(Phase.DONE, null, 0, INDETERMINATE, ETA_UNKNOWN, failedModules, 0L);
    }

    ModuleQueueState withSeq(long seq) {
        return new ModuleQueueState(phase, currentModule, remaining, percent, etaSeconds, failedModules, seq);
    }
}
