/*
 * ============================================================================
 * Name        : InstallHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Seam between DeployFragment and InstallController. The install
 *               pipeline is deeply coupled to fragment-wide state, so that state
 *               stays on the Fragment and is reached through this interface;
 *               managers (aria2, proot) are exposed here too.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.widget.CheckBox;

import org.iiab.controller.Aria2Manager;
import org.iiab.controller.PRootEngine;

import org.json.JSONObject;

import java.util.List;

public interface InstallHost {
    // selection / projection state (shared with the planner + dynamic buttons)
    org.iiab.controller.InstallationPlanner.Tier getSelectedTier();
    List<CheckBox> moduleCheckboxes();
    // ADFA-4476 slice 1: module-grid selection lives in the Activity-scoped
    // ViewModel so it survives recreation; reached through this seam.
    java.util.Set<String> selectedModuleKeys();
    boolean isStorageSafe();
    boolean hasInternet();
    String getOverrideKiwixLang();
    String getOverrideKiwixVariant();
    // install state (kept on the Fragment because other areas read it)
    boolean isDownloadingRootfs();
    void setDownloadingRootfs(boolean v);
    boolean isBatchInstalling();
    void setBatchInstalling(boolean v);
    List<String> installationQueue();
    JSONObject getLastKnownState();
    void setLastKnownState(JSONObject v);
    // shared managers
    Aria2Manager aria2Manager();
    void setAria2Manager(Aria2Manager v);
    PRootEngine prootEngine();
    void setPRootEngine(PRootEngine v);
    // shared helpers / cross-feature
    void updateDynamicButtons();
    String getTermuxArch();
    boolean isSystemBusy();
    String getSystemBusyMessage();
    void enableSystemProtection();
    void disableSystemProtection();
    boolean pingUrl(String url);
    void requestFreshLocalVarsSilently();
}
