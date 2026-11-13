/*
 * ============================================================================
 * Name        : ApplyPendingRestartsUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. At app open, restart the services whose change waits for a restart.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

import java.util.ArrayList;
import java.util.List;

/** "Later" and the one-time off on update apply here. Pending is derived by the box (config newer
 *  than the running process), so nothing is stored: a restart clears it by itself. */
public final class ApplyPendingRestartsUseCase {

    private final PrivacyRepository repository;
    private final EnvironmentGate gate;

    public ApplyPendingRestartsUseCase(PrivacyRepository repository, EnvironmentGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    /** Returns the services it asked to restart; none while a destructive operation runs. */
    public List<String> execute() throws PrivacyException {
        List<String> restarted = new ArrayList<>();
        if (gate.isBusy()) return restarted;
        for (OutboundSwitch s : repository.list()) {
            if (s.installed() && s.pendingRestart()) {
                repository.requestRestart(s.service());
                restarted.add(s.service());
            }
        }
        return restarted;
    }
}
