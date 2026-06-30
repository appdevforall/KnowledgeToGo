/*
 * ============================================================================
 * Name        : SyncProgressRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped, observable single source of truth for the Share
 *               rsync TRANSFER progress. Being process-scoped (not tied to a
 *               Fragment/Activity), the UI re-binds to the current state after a
 *               configuration-change recreation (theme toggle). The transfer's
 *               TransportEngine.SyncListener is the writer; SyncFragment is the
 *               reader. Mirrors InstallProgressRepository. ADFA-4492 (3b-2).
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public final class SyncProgressRepository {

    private static final SyncProgressRepository INSTANCE = new SyncProgressRepository();

    public static SyncProgressRepository get() {
        return INSTANCE;
    }

    private final MutableLiveData<SyncTransferState> state = new MutableLiveData<>(SyncTransferState.idle());
    private long seq = 0L;

    private SyncProgressRepository() {
    }

    public LiveData<SyncTransferState> state() {
        return state;
    }

    public SyncTransferState current() {
        SyncTransferState s = state.getValue();
        return s != null ? s : SyncTransferState.idle();
    }

    /** True while a transfer is in flight (used to decide whether to survive a recreation). */
    public boolean isActive() {
        return current().isActive();
    }

    // Thread-safe (callable from the rsync worker thread via the listener).
    public void postTransferring(int percent, String speed, String eta, String file) {
        post(SyncTransferState.transferring(percent, speed, eta, file));
    }

    public void postSuccess(String message) { post(SyncTransferState.success(message)); }
    public void postFailed(String message)  { post(SyncTransferState.failed(message)); }
    public void postIdle()                  { post(SyncTransferState.idle()); }

    // Pre-transfer probing phases (ADFA-4492 step 4): published so the sondeo survives recreation.
    public void postConnecting()                         { post(SyncTransferState.connecting()); }
    public void postCalculating()                        { post(SyncTransferState.calculating()); }
    public void postConfirm(String title, String msg)   { post(SyncTransferState.confirm(title, msg)); }
    public void postAborted(String title, String msg)   { post(SyncTransferState.aborted(title, msg)); }

    private synchronized void post(SyncTransferState s) {
        state.postValue(s.withSeq(++seq));
    }
}
