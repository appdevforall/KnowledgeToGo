/*
 * ============================================================================
 * Name        : DownloadStateViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Owns the rootfs/ZIM download state (the Aria2Manager + the
 *               in-flight flag) at Activity scope, so it survives DeployFragment
 *               recreation (e.g. a rotation mid-download) WITHOUT static mutable
 *               fields. Replaces DeployFragment's `static Aria2Manager` +
 *               `static isDownloadingRootfs` (ADFA-4459, D9).
 *
 *               Pure state holder: no Android UI and no lifecycle teardown here
 *               -- the Fragment still decides when to stop the download
 *               (onDestroyView, unless it is only changing configuration).
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.Aria2Manager;

public class DownloadStateViewModel extends ViewModel {

    private Aria2Manager aria2Manager;
    private boolean downloadingRootfs;

    public Aria2Manager getAria2Manager() {
        return aria2Manager;
    }

    public void setAria2Manager(Aria2Manager aria2Manager) {
        this.aria2Manager = aria2Manager;
    }

    public boolean isDownloadingRootfs() {
        return downloadingRootfs;
    }

    public void setDownloadingRootfs(boolean downloadingRootfs) {
        this.downloadingRootfs = downloadingRootfs;
    }

    /** Observable install-pipeline progress (app-scoped; survives recreation). */
    public LiveData<InstallState> installState() {
        return InstallProgressRepository.get().state();
    }
}
