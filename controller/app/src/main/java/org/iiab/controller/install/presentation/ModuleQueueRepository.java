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
package org.iiab.controller.install.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

public final class ModuleQueueRepository {

    private static final ModuleQueueRepository INSTANCE = new ModuleQueueRepository();

    public static ModuleQueueRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<ModuleQueueState> state = new MutableLiveData<>(ModuleQueueState.idle());
    private long seq = 0L;

    private ModuleQueueRepository() {
    }

    public LiveData<ModuleQueueState> state() {
        return state;
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
    public void postDone(List<String> failedModules)             { post(ModuleQueueState.done(failedModules)); }
    public void postIdle()                                       { post(ModuleQueueState.idle()); }

    private synchronized void post(ModuleQueueState s) {
        state.postValue(s.withSeq(++seq));
    }
}
