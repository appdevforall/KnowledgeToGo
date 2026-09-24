/*
 * ============================================================================
 * Name        : PortalViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Holds portal load state + resolved target URL across configuration changes.
 * ============================================================================
 */
package org.appdevforall.k2go.portal.presentation;

import android.os.SystemClock;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.portal.domain.PortalUrlResolver;
import org.appdevforall.k2go.util.ResilientWebViewClient;

/** Survives rotation: keeps the resolved target URL and the page-load state. */
public class PortalViewModel extends ViewModel {

    private final MutableLiveData<PortalUiState> state = new MutableLiveData<>(PortalUiState.idle());
    private String targetUrl;

    // K2GO-419: renderer-crash recovery bookkeeping. The ViewModel outlives Activity.recreate(), so
    // this is the one place that remembers "we just recovered" across the rebuild, so a page whose
    // renderer keeps dying is not reloaded in an endless recreate loop. Window source: ResilientWebViewClient.
    private long lastRendererRecoveryMs = 0L;

    public LiveData<PortalUiState> state() { return state; }

    public boolean isLoading() {
        PortalUiState s = state.getValue();
        return s != null && s.loading;
    }

    public void setLoading(boolean loading) {
        state.setValue(loading ? PortalUiState.loading() : PortalUiState.idle());
    }

    /** Resolve once and remember; subsequent calls keep the first resolved value. */
    public String targetUrl(String rawUrl) {
        if (targetUrl == null) {
            targetUrl = PortalUrlResolver.resolve(rawUrl);
        }
        return targetUrl;
    }

    /**
     * K2GO-419: true when the portal may auto-recover (rebuild + reload) after a renderer death.
     * False when a second death lands within {@link ResilientWebViewClient#RECOVERY_WINDOW_MS}, so the caller
     * breaks the loop (inform the user and leave) instead of reloading a page that keeps killing the
     * renderer. Records this attempt's time. Uses the monotonic clock so a wall-clock change cannot
     * skew the window.
     */
    public boolean allowRendererAutoRecovery() {
        long now = SystemClock.elapsedRealtime();
        boolean recentlyRecovered = lastRendererRecoveryMs != 0L
                && (now - lastRendererRecoveryMs) < ResilientWebViewClient.RECOVERY_WINDOW_MS;
        lastRendererRecoveryMs = now;
        return !recentlyRecovered;
    }
}
