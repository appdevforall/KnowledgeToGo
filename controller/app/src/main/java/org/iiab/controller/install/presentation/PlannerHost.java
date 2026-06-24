/*
 * ============================================================================
 * Name        : PlannerHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Seam between DeployFragment and PlannerController. "What to
 *               install" state (tier, module checkboxes, kiwix overrides) stays
 *               on the Fragment because updateDynamicButtons + the install-action
 *               also read it; the controller reaches it through this interface.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.widget.CheckBox;

import java.util.List;

public interface PlannerHost {
    org.iiab.controller.InstallationPlanner.Tier getSelectedTier();
    void setSelectedTier(org.iiab.controller.InstallationPlanner.Tier tier);
    List<CheckBox> moduleCheckboxes();
    void setStorageSafe(boolean safe);
    boolean isStorageSafe();
    String getOverrideKiwixLang();
    void setOverrideKiwixLang(String lang);
    String getOverrideKiwixVariant();
    void setOverrideKiwixVariant(String variant);
    boolean hasInternet();
    void updateDynamicButtons();
    String getTermuxArch();
}
