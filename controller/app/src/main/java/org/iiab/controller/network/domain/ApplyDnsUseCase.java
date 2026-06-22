/*
 * ============================================================================
 * Name        : ApplyDnsUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Use case: write the effective DNS config into a rootfs (boot-time apply).
 * ============================================================================
 */
package org.iiab.controller.network.domain;

import java.io.File;

/**
 * Writes the effective DNS config (custom or defaults) into a guest rootfs. Called
 * at server boot (and wherever a rootfs is about to run network operations) so the
 * proot guest always has a working {@code resolv.conf} without a resolver daemon.
 */
public final class ApplyDnsUseCase {

    private final DnsConfigRepository repository;
    private final ResolvConfWriter writer;

    public ApplyDnsUseCase(DnsConfigRepository repository, ResolvConfWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    /** @return {@code true} if resolv.conf was written into {@code rootfsDir}. */
    public boolean execute(File rootfsDir) {
        return writer.write(repository.loadEffective(), rootfsDir);
    }
}
