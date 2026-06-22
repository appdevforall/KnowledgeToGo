/*
 * ============================================================================
 * Name        : SaveDnsConfigUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Use case: validate then persist a user-entered DNS config.
 * ============================================================================
 */
package org.iiab.controller.network.domain;

/**
 * Validates a user-entered {@link DnsConfig} and, only if valid, persists it as the
 * custom config (enabling custom DNS). Returns the validation {@link DnsValidator.Result}
 * so the UI can show why it was rejected.
 */
public final class SaveDnsConfigUseCase {

    private final DnsConfigRepository repository;

    public SaveDnsConfigUseCase(DnsConfigRepository repository) {
        this.repository = repository;
    }

    public DnsValidator.Result execute(DnsConfig config) {
        DnsValidator.Result result = DnsValidator.validate(config);
        if (result.valid) {
            repository.saveCustom(config);
        }
        return result;
    }
}
