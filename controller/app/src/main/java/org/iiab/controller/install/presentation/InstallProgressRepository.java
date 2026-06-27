/*
 * ============================================================================
 * Name        : InstallProgressRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped, observable single source of truth for the rootfs
 *               install pipeline's progress. Being process-scoped (not tied to a
 *               Fragment/Activity), the UI re-binds to the current InstallState
 *               after a configuration-change recreation (e.g. theme toggle).
 *               (ADFA-4474.)
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public final class InstallProgressRepository {

    private static final InstallProgressRepository INSTANCE = new InstallProgressRepository();

    public static InstallProgressRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<InstallState> state = new MutableLiveData<>(InstallState.idle());

    private InstallProgressRepository() {
    }

    public LiveData<InstallState> state() {
        return state;
    }

    /** Thread-safe (callable from the aria2 worker thread). */
    public void postDownloading(int percent, String speed) {
        state.postValue(InstallState.downloading(percent, speed));
    }

    public void postPhase(InstallState.Phase phase) {
        state.postValue(InstallState.phase(phase));
    }

    public void postIdle() {
        state.postValue(InstallState.idle());
    }
}
