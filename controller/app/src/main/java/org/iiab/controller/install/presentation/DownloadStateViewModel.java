/*
 * ============================================================================
 * Name        : DownloadStateViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Owns the rootfs/ZIM download state (the Aria2Manager + the
 *               in-flight flag) at Activity scope, so it survives DeployFragment
 *               recreation (e.g. a rotation mid-download) WITHOUT static mutable
 *               fields. Replaces DeployFragment's `static Aria2Manager` +
 *               `static isDownloadingRootfs` (ADFA-4459, D9). ADFA-4474 PR3 also
 *               holds the planner SELECTION (tier, companion-data, kiwix overrides)
 *               here so the projection gauge + selections survive recreation.
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
import org.iiab.controller.InstallationPlanner;

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

    // ADFA-4474 PR3: planner selection state, kept at Activity scope so the
    // projection gauge + tier/companion/kiwix selections survive a recreation
    // (theme toggle / rotation / background+return).
    private InstallationPlanner.Tier selectedTier;
    private boolean companionData;
    private String overrideKiwixLang;
    private String overrideKiwixVariant;

    public InstallationPlanner.Tier getSelectedTier() { return selectedTier; }
    public void setSelectedTier(InstallationPlanner.Tier tier) { this.selectedTier = tier; }

    public boolean isCompanionData() { return companionData; }
    public void setCompanionData(boolean companionData) { this.companionData = companionData; }

    public String getOverrideKiwixLang() { return overrideKiwixLang; }
    public void setOverrideKiwixLang(String lang) { this.overrideKiwixLang = lang; }

    public String getOverrideKiwixVariant() { return overrideKiwixVariant; }
    public void setOverrideKiwixVariant(String variant) { this.overrideKiwixVariant = variant; }

    // ADFA-4476 slice 1: module-grid checkbox selection, kept at Activity scope so
    // the checked modules survive a recreation (theme toggle / rotation /
    // background+return), the same seam as the tier/companion/kiwix selections.
    // Previously an InstallController (Fragment-scoped) field, so it was lost on
    // recreation while verifyInstallationState re-rendered the cards unchecked.
    private final java.util.Set<String> selectedModuleKeys = new java.util.HashSet<>();
    public java.util.Set<String> getSelectedModuleKeys() { return selectedModuleKeys; }

    /** Observable install-pipeline progress (app-scoped; survives recreation). */
    public LiveData<InstallState> installState() {
        return InstallProgressRepository.get().state();
    }
}
