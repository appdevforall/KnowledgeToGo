/*
 * ============================================================================
 * Name        : ModuleDownloadRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394, K2GO-593. The one place where the progress of a
 *               module's download lives, so the install screens observe it.
 * ============================================================================
 */
package org.appdevforall.k2go.install.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.appdevforall.k2go.install.domain.ModuleDownloadProgress;

/**
 * The download bar's single source. {@link ModuleDownload} writes it from the dash-node poll; the
 * install screens observe it beside {@code ModuleQueueRepository} (the phase spine). One writer, so no
 * second place makes up a download percent. It is {@link ModuleDownloadProgress#none()} when no
 * module downloads, so a module with no download shows only its phase.
 */
public final class ModuleDownloadRepository {

    private static final ModuleDownloadRepository INSTANCE = new ModuleDownloadRepository();

    private final MutableLiveData<ModuleDownloadProgress> state =
            new MutableLiveData<>(ModuleDownloadProgress.none());
    // postValue is applied later on the main thread; current() must see the last write at once.
    private volatile ModuleDownloadProgress last = ModuleDownloadProgress.none();

    private ModuleDownloadRepository() {
    }

    public static ModuleDownloadRepository get() {
        return INSTANCE;
    }

    public LiveData<ModuleDownloadProgress> state() {
        return state;
    }

    public ModuleDownloadProgress current() {
        return last;
    }

    /** Post a new snapshot. postValue, because the writers run on the main and on worker threads. */
    public void post(ModuleDownloadProgress p) {
        last = p != null ? p : ModuleDownloadProgress.none();
        state.postValue(last);
    }

    /** Back to "nothing downloads": when the download ends, and on teardown. */
    public void clear() {
        post(ModuleDownloadProgress.none());
    }
}
