/*
 * ============================================================================
 * Name        : ServerState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable snapshot of the native server's app-level state
 *               (ADFA-4578, slice 1). Held by ServerStateRepository so every tab
 *               can observe "server up/down" + the derived SystemState live,
 *               instead of only after visiting the Dashboard.
 * ============================================================================
 */
package org.iiab.controller;

public final class ServerState {

    public final boolean alive;
    public final DashboardFragment.SystemState systemState;

    private ServerState(boolean alive, DashboardFragment.SystemState systemState) {
        this.alive = alive;
        this.systemState = systemState;
    }

    public static ServerState of(boolean alive, DashboardFragment.SystemState systemState) {
        return new ServerState(alive, systemState);
    }

    public static ServerState unknown() {
        return new ServerState(false, DashboardFragment.SystemState.NONE);
    }
}
