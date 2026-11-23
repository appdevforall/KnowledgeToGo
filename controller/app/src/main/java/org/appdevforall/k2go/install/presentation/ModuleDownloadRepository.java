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
 * dash-node poll ({@code RestContentClient.Listener} for the "basemaps" job); the progress screen
 * observes it beside {@code ModuleQueueRepository} (the phase spine). One writer, so there is no
 * second place inventing a download percent.
 *
 * <p>Starts and resets to {@link MapsDownloadProgress#none()} -- the honest "nothing to show", which
 * is also what a non-maps module leaves it at, so the UI simply shows the phase-only Variant 3.
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

    /**
     * Post a new snapshot. {@code postValue} on purpose: the poll listener delivers on the main thread
     * but InstallService clears from its background install thread, so the write must be thread-safe.
     */
    public void post(MapsDownloadProgress p) {
        state.postValue(p != null ? p : MapsDownloadProgress.none());
    }

    /** Clear back to "nothing downloading" -- on teardown, or when the download finishes. */
    public void clear() {
        state.postValue(MapsDownloadProgress.none());
    }
}
