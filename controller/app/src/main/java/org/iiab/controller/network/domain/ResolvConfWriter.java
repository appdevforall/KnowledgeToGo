/*
 * ============================================================================
 * Name        : ResolvConfWriter.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain port: write resolv.conf (+ hosts) into a rootfs.
 * ============================================================================
 */
package org.iiab.controller.network.domain;

import java.io.File;

/**
 * Domain port that writes a {@link DnsConfig} into a guest rootfs as
 * {@code etc/resolv.conf} (plus a minimal {@code etc/hosts}). The Data layer
 * implements the actual file I/O and logging. Implementations must never throw.
 */
public interface ResolvConfWriter {

    /**
     * Write {@code config} into {@code rootfsDir}/etc.
     *
     * @return {@code true} if written; {@code false} if skipped (e.g. {@code etc/} missing)
     *         or on a handled error.
     */
    boolean write(DnsConfig config, File rootfsDir);
}
