/*
 * ============================================================================
 * Name        : RestartServiceUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. "Restart now": ask the lifecycle owner to restart one service.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

/** Rule: only a destructive box operation blocks a restart; live work (downloads, sharing, someone
 *  using the service) does not, because the user chose to restart. */
public final class RestartServiceUseCase {

    public enum Result { RESTARTING, BUSY, FAILED }

    private final PrivacyRepository repository;
    private final EnvironmentGate gate;

    public RestartServiceUseCase(PrivacyRepository repository, EnvironmentGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    public Result execute(String service) {
        if (gate.isBusy()) return Result.BUSY;
        try {
            repository.requestRestart(service);
            return Result.RESTARTING;
        } catch (PrivacyException e) {
            return Result.FAILED;
        }
    }
}
