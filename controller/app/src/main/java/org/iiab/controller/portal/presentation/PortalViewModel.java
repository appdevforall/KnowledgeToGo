/*
 * ============================================================================
 * Name        : PortalViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Holds portal load state + resolved target URL across configuration changes.
 * ============================================================================
 */
package org.iiab.controller.portal.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.portal.domain.PortalUrlResolver;

/** Survives rotation: keeps the resolved target URL and the page-load state. */
public class PortalViewModel extends ViewModel {

    private final MutableLiveData<PortalUiState> state = new MutableLiveData<>(PortalUiState.idle());
    private String targetUrl;

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
}
