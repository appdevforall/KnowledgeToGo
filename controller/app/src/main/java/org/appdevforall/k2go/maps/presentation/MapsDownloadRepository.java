/*
 * ============================================================================
 * Name        : MapsDownloadRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. The one place the maps download progress lives, so the
 *               install-progress UI observes it instead of polling aria2 itself.
 * ============================================================================
 */
package org.appdevforall.k2go.maps.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.appdevforall.k2go.maps.domain.MapsDownloadProgress;

/**
 * The subordinate download bar's single source (K2GO-394). {@code InstallService} writes it from the
 * {@code MapsDownloadRpc} listener; the progress screen observes it beside {@code ModuleQueueRepository}
 * (the phase spine). One writer, so there is no second place inventing a download percent.
 *
 * <p>Starts and resets to {@link MapsDownloadProgress#none()} -- the honest "nothing to show", which
 * is also what a stock rootfs (no RPC) leaves it at, so the UI simply shows the phase-only Variant 3.
 */
public final class MapsDownloadRepository {

    private static final MapsDownloadRepository INSTANCE = new MapsDownloadRepository();

    private final MutableLiveData<MapsDownloadProgress> state =
            new MutableLiveData<>(MapsDownloadProgress.none());

    private MapsDownloadRepository() {
    }

    public static MapsDownloadRepository get() {
        return INSTANCE;
    }

    public LiveData<MapsDownloadProgress> state() {
        return state;
    }

    public MapsDownloadProgress current() {
        MapsDownloadProgress v = state.getValue();
        return v != null ? v : MapsDownloadProgress.none();
    }

    /** Post a new snapshot (main thread only, as MapsDownloadRpc delivers on the main thread). */
    public void post(MapsDownloadProgress p) {
        state.setValue(p != null ? p : MapsDownloadProgress.none());
    }

    /** Clear back to "nothing downloading" -- on teardown, or when the RPC goes idle. */
    public void clear() {
        state.setValue(MapsDownloadProgress.none());
    }
}
