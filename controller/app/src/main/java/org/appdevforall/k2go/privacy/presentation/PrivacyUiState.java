/*
 * ============================================================================
 * Name        : PrivacyUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. What the Privacy screen shows.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.presentation;

import org.appdevforall.k2go.privacy.domain.OutboundSwitch;

import java.util.Collections;
import java.util.List;

public final class PrivacyUiState {

    public enum Status { LOADING, READY, UNSUPPORTED, UNREACHABLE, FAILED }

    public final Status status;
    public final List<OutboundSwitch> switches;
    /** The service whose change is in flight, or null. The switches are locked while it is set. */
    public final String busyService;

    PrivacyUiState(Status status, List<OutboundSwitch> switches, String busyService) {
        this.status = status;
        this.switches = Collections.unmodifiableList(switches);
        this.busyService = busyService;
    }

    public boolean busy() { return busyService != null; }
}
