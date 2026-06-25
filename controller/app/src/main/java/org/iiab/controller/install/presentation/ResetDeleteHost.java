/*
 * ============================================================================
 * Name        : ResetDeleteHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Seam between DeployFragment and ResetDeleteController. Most state
 *               is shared with the install flow and stays on the Fragment.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import org.iiab.controller.Aria2Manager;
import org.iiab.controller.PRootEngine;

public interface ResetDeleteHost {
    void updateDynamicButtons();
    String getTermuxArch();
    boolean isSystemBusy();
    String getSystemBusyMessage();
    void enableSystemProtection();
    void disableSystemProtection();
    boolean isDeleting();
    void setDeleting(boolean v);
    boolean isDownloadingRootfs();
    void setDownloadingRootfs(boolean v);
    Aria2Manager aria2Manager();
    void setAria2Manager(Aria2Manager v);
    PRootEngine prootEngine();
    void setPRootEngine(PRootEngine v);
}
