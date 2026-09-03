/*
 * ============================================================================
 * Name        : DashboardStatus.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable snapshot of the dashboard's network status: whether the
 *               local server is alive and, per module endpoint, whether it responds.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.presentation;

import java.util.Collections;
import java.util.Map;

public final class DashboardStatus {

    private final boolean serverAlive;
    private final Map<String, Boolean> moduleAlive;

    public DashboardStatus(boolean serverAlive, Map<String, Boolean> moduleAlive) {
        this.serverAlive = serverAlive;
        this.moduleAlive = moduleAlive != null ? moduleAlive : Collections.emptyMap();
    }

    public boolean serverAlive() {
        return serverAlive;
    }

    public Map<String, Boolean> moduleAlive() {
        return moduleAlive;
    }
}
