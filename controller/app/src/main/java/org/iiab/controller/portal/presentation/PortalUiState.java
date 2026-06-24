/*
 * ============================================================================
 * Name        : PortalUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable UI state for the portal screen (page load status).
 * ============================================================================
 */
package org.iiab.controller.portal.presentation;

/** Minimal portal UI state: whether the WebView is currently loading a page. */
public final class PortalUiState {

    public final boolean loading;

    public PortalUiState(boolean loading) {
        this.loading = loading;
    }

    public static PortalUiState idle() { return new PortalUiState(false); }
    public static PortalUiState loading() { return new PortalUiState(true); }
}
