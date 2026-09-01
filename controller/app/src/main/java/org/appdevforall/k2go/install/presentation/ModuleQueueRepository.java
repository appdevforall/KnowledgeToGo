/*
 * ============================================================================
 * Name        : ModuleQueueRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped, observable single source of truth for the per-module
 *               install queue (ADFA-4476 slice 3). The foreground InstallService
 *               is the writer (it owns the dequeue loop); the module grid + the
 *               launch button are the readers and re-bind after a recreation
 *               (theme toggle / rotation) or backgrounding. Mirrors
 *               InstallProgressRepository; being process-scoped, it survives the
 *               Fragment/Activity lifecycle so a recreation mid-queue never
 *               launches a second concurrent runrole.
 * ============================================================================
 */
package org.appdevforall.k2go.install.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

public final class ModuleQueueRepository {

    private static final ModuleQueueRepository INSTANCE = new ModuleQueueRepository();

    public static ModuleQueueRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<ModuleQueueState> state = new MutableLiveData<>(ModuleQueueState.idle());
    /** ADFA-4898 P4: movement-based stall hint for the current module (surface only, never auto-kill).
     *  Kept separate from the immutable ModuleQueueState so a transient hint never rewrites the queue. */
    private final MutableLiveData<Boolean> stalled = new MutableLiveData<>(false);
    private long seq = 0L;

    private ModuleQueueRepository() {
    }

    public LiveData<ModuleQueueState> state() {
        return state;
    }

    /** ADFA-4898 P4: true while the current module's runrole has shown no movement (log line or write-dir
     *  growth) for the stall window. A surface-only hint — the install keeps running. */
    public LiveData<Boolean> stalled() {
        return stalled;
    }

    public void postStalled(boolean isStalled) {
        stalled.postValue(isStalled);
    }

    public ModuleQueueState current() {
        ModuleQueueState s = state.getValue();
        return s != null ? s : ModuleQueueState.idle();
    }

    /** True while the queue loop is running (a module's runrole is in flight). */
    public boolean isRunning() {
        return current().isRunning();
    }

    /** The module being installed right now, or null. */
    public String currentModule() {
        return current().currentModule;
    }

    /** True when {@code moduleKey} is the module currently being installed. */
    public boolean isInstalling(String moduleKey) {
        return current().isInstalling(moduleKey);
    }

    // Thread-safe posts (callable from the proot worker callbacks).
    public void postRunning(String currentModule, int remaining) { post(ModuleQueueState.running(currentModule, remaining)); }
    /** ADFA-5228: running with a determinate percent (0..100) for the current module's runrole. */
    public void postRunning(String currentModule, int remaining, int percent) { post(ModuleQueueState.running(currentModule, remaining, percent)); }
    /** ADFA-5228: running with a determinate percent and an estimated seconds-remaining. */
    public void postRunning(String currentModule, int remaining, int percent, long etaSeconds) { post(ModuleQueueState.running(currentModule, remaining, percent, etaSeconds)); }
    public void postDone(List<String> failedModules)             { stalled.postValue(false); post(ModuleQueueState.done(failedModules)); }
    public void postIdle()                                       { stalled.postValue(false); post(ModuleQueueState.idle()); }

    private synchronized void post(ModuleQueueState s) {
        state.postValue(s.withSeq(++seq));
    }
}
