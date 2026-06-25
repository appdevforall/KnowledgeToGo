/*
 * ============================================================================
 * Name        : ConfigureDnsUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Validate, netplan-try probe, then save (or revert to defaults).
 * ============================================================================
 */
package org.iiab.controller.network.domain;

/**
 * Orchestrates applying a user-entered DNS config, netplan-try style:
 * <ol>
 *   <li>validate (fail-closed) — invalid input is never saved;</li>
 *   <li>probe the server(s) for an actual DNS reply;</li>
 *   <li>if reachable, persist as custom; otherwise revert to defaults and report.</li>
 * </ol>
 * Pure domain logic over the {@link DnsConfigRepository} and {@link DnsConnectivityProbe}
 * ports — unit-testable with fakes.
 */
public final class ConfigureDnsUseCase {

    public enum Outcome { APPLIED, INVALID, UNREACHABLE }

    public static final class Result {
        public final Outcome outcome;
        public final String message;
        private Result(Outcome outcome, String message) { this.outcome = outcome; this.message = message; }
        static Result applied() { return new Result(Outcome.APPLIED, null); }
        static Result invalid(String reason) { return new Result(Outcome.INVALID, reason); }
        static Result unreachable() { return new Result(Outcome.UNREACHABLE, "DNS did not respond"); }
    }

    private static final long DEFAULT_TIMEOUT_MS = 4000L;

    private final DnsConfigRepository repository;
    private final DnsConnectivityProbe probe;
    private final long timeoutMs;

    public ConfigureDnsUseCase(DnsConfigRepository repository, DnsConnectivityProbe probe) {
        this(repository, probe, DEFAULT_TIMEOUT_MS);
    }

    public ConfigureDnsUseCase(DnsConfigRepository repository, DnsConnectivityProbe probe, long timeoutMs) {
        this.repository = repository;
        this.probe = probe;
        this.timeoutMs = timeoutMs;
    }

    public Result execute(DnsConfig candidate) {
        DnsValidator.Result validation = DnsValidator.validate(candidate);
        if (!validation.valid) {
            return Result.invalid(validation.reason);
        }
        boolean reachable = probe.isReachable(candidate.primary(), timeoutMs);
        if (!reachable && candidate.hasSecondary()) {
            reachable = probe.isReachable(candidate.secondary(), timeoutMs);
        }
        if (reachable) {
            repository.saveCustom(candidate);
            return Result.applied();
        }
        repository.disableCustom();
        return Result.unreachable();
    }
}
