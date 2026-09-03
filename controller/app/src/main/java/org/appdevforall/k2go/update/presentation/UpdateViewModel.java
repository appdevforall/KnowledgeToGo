/*
 * ============================================================================
 * Name        : UpdateViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Polls the OTA download and exposes UpdateUiState; supports cancel.
 * ============================================================================
 */
package org.appdevforall.k2go.update.presentation;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.update.domain.DownloadProgress;
import org.appdevforall.k2go.update.domain.OtaDownloadGateway;

/**
 * Drives the in-app OTA update dialog. While a download is tracked it polls the
 * {@link OtaDownloadGateway} (~400ms) and posts {@link UpdateUiState}. When the
 * download finishes (SUCCESSFUL) it moves to VERIFYING; MainActivity then verifies
 * the APK signature and calls {@link #onReady()} / {@link #onError(String)}.
 */
public class UpdateViewModel extends ViewModel {

    private static final long POLL_MS = 400;

    private final OtaDownloadGateway gateway;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<UpdateUiState> state = new MutableLiveData<>(UpdateUiState.idle());

    private long downloadId = -1;
    private Runnable poller;
    private Runnable onTerminal;
    private boolean terminalFired;

    public UpdateViewModel(OtaDownloadGateway gateway) {
        this.gateway = gateway;
    }

    public LiveData<UpdateUiState> state() {
        return state;
    }

    /**
     * Callback fired once when the tracked download reaches a terminal state
     * (SUCCESSFUL or FAILED). This is the reliable completion signal — it comes
     * from polling DownloadManager directly, so it fires even if the system's
     * ACTION_DOWNLOAD_COMPLETE broadcast is never delivered (app backgrounded,
     * OEM quirks). The controller uses it to run signature verification instead
     * of relying solely on the broadcast (which could hang the dialog on
     * "verifying" forever). Safe to call again; only the first terminal poll fires.
     */
    public void setOnTerminal(Runnable r) {
        this.onTerminal = r;
    }

    /** Start tracking a DownloadManager download id; polls until it is terminal. */
    public void track(long id) {
        downloadId = id;
        terminalFired = false;
        stopPolling();
        poller = new Runnable() {
            @Override public void run() {
                PollOutcome o = decidePoll(gateway.query(downloadId), terminalFired);
                if (o.state != null) state.setValue(o.state);
                if (o.fireTerminal) {
                    terminalFired = true;
                    if (onTerminal != null) onTerminal.run();
                }
                if (o.keepPolling) handler.postDelayed(this, POLL_MS);
            }
        };
        handler.post(poller);
    }

    /**
     * Called by the controller when completion has been handled via the
     * DownloadManager broadcast (the fast path). Marks the terminal state as
     * already handled and stops polling, so a later poll tick cannot regress the
     * dialog (e.g. overwrite a freshly-posted READY back to VERIFYING).
     */
    public void markTerminalHandled() {
        terminalFired = true;
        stopPolling();
    }

    public void onReady() { state.setValue(UpdateUiState.ready()); }
    public void onInstalling() { state.setValue(UpdateUiState.installing()); }
    public void onError(String message) { stopPolling(); state.setValue(UpdateUiState.error(message)); }

    /** User cancelled: remove the download and reset. */
    public void cancel() {
        stopPolling();
        if (downloadId >= 0) gateway.cancel(downloadId);
        downloadId = -1;
        terminalFired = false;
        state.setValue(UpdateUiState.idle());
    }

    /**
     * Pure decision for a single poll tick — no Android, so it is unit-testable.
     * Once a terminal state has already been handled (by an earlier poll or by the
     * broadcast fast path), a later terminal observation must NOT re-emit VERIFYING;
     * that is what previously regressed a completed READY back to "verifying" and
     * re-hung the dialog. In that case emit no state and stop polling.
     */
    static PollOutcome decidePoll(DownloadProgress p, boolean terminalAlreadyHandled) {
        if (p.isTerminal()) {
            if (terminalAlreadyHandled) {
                return new PollOutcome(null, false, false);
            }
            return new PollOutcome(UpdateUiState.fromDownload(p), true, false);
        }
        return new PollOutcome(UpdateUiState.fromDownload(p), false, true);
    }

    /** Result of one poll tick: which state to emit (null = none), whether to fire the terminal callback, whether to keep polling. */
    static final class PollOutcome {
        final UpdateUiState state;
        final boolean fireTerminal;
        final boolean keepPolling;

        PollOutcome(UpdateUiState state, boolean fireTerminal, boolean keepPolling) {
            this.state = state;
            this.fireTerminal = fireTerminal;
            this.keepPolling = keepPolling;
        }
    }

    private void stopPolling() {
        if (poller != null) handler.removeCallbacks(poller);
        poller = null;
    }

    @Override
    protected void onCleared() {
        stopPolling();
    }
}
