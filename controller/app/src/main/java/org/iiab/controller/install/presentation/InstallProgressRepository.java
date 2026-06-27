/*
 * ============================================================================
 * Name        : InstallProgressRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped, observable single source of truth for the rootfs
 *               install pipeline's progress. Being process-scoped (not tied to a
 *               Fragment/Activity), the UI re-binds to the current InstallState
 *               after a configuration-change recreation (e.g. theme toggle) and
 *               after backgrounding. The foreground InstallService is the writer;
 *               the install UI is the reader. (ADFA-4474.)
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
    private long seq = 0L;

    private InstallProgressRepository() {
    }

    public LiveData<InstallState> state() {
        return state;
    }

    public InstallState current() {
        InstallState s = state.getValue();
        return s != null ? s : InstallState.idle();
    }

    /** True while a (non-terminal) install is in flight. Single source of truth. */
    public boolean isRunning() {
        return current().isRunning();
    }

    // All posts are thread-safe (callable from the aria2 / proot worker threads).
    public void postDownloading(int percent, String speed) { post(InstallState.downloading(percent, speed)); }
    public void postExtracting(String message)             { post(InstallState.extracting(message)); }
    public void postProvisioning(String message)           { post(InstallState.provisioning(message)); }
    public void postSuccess()                               { post(InstallState.success()); }
    public void postFailed(String message)                  { post(InstallState.failed(message)); }
    public void postIdle()                                  { post(InstallState.idle()); }

    private synchronized void post(InstallState s) {
        state.postValue(s.withSeq(++seq));
    }
}
