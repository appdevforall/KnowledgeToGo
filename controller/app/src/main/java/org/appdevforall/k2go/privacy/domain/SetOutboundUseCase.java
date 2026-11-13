/*
 * ============================================================================
 * Name        : SetOutboundUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. Set one switch; ask for the service restart only when the box is free.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.domain;

/**
 * Rule: never change a service config while an exclusive box operation runs (an install writes the
 * same files and stops the services). After a change, the service needs a restart: ask for it only
 * when the box is still free; else the service reads the new config at its next start.
 */
public final class SetOutboundUseCase {

    public enum Result {
        /** An exclusive operation holds the box: nothing was written. */
        BUSY,
        /** The box already had this value: nothing to restart. */
        UNCHANGED,
        /** Written; the restart of that service was requested. */
        RESTARTING,
        /** Written; the service applies it at its next start. */
        NEXT_START
    }

    private final PrivacyRepository repository;
    private final EnvironmentGate gate;

    public SetOutboundUseCase(PrivacyRepository repository, EnvironmentGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    /** Throws when the box did not take the change. */
    public Result execute(String service, boolean outbound) throws PrivacyException {
        if (gate.isBusy()) return Result.BUSY;
        if (!repository.set(service, outbound)) return Result.UNCHANGED;
        if (gate.isBusy()) return Result.NEXT_START;   // an operation started meanwhile
        try {
            repository.requestRestart(service);
            return Result.RESTARTING;
        } catch (PrivacyException e) {
            return Result.NEXT_START;
        }
    }
}
