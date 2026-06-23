/*
 * ============================================================================
 * Name        : GetDnsConfigUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Use case: read the effective DNS config (custom or defaults).
 * ============================================================================
 */
package org.iiab.controller.network.domain;

/** Returns the DNS config the system should use right now (custom when enabled, else defaults). */
public final class GetDnsConfigUseCase {

    private final DnsConfigRepository repository;

    public GetDnsConfigUseCase(DnsConfigRepository repository) {
        this.repository = repository;
    }

    public DnsConfig execute() {
        return repository.loadEffective();
    }
}
