/*
 * ============================================================================
 * Name        : ExpectedArchive.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-569/570. What a companion file (X.tar.json or X.tar.gz.minisig) says
 *               about its tarball: compressed size and sha-256, and the uncompressed tar
 *               size. A restore whose copy matches it skips the listing. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

import java.util.Locale;

public final class ExpectedArchive {

    public final long gzBytes;
    public final String gzSha256;
    public final long tarBytes;

    public ExpectedArchive(long gzBytes, String gzSha256, long tarBytes) {
        this.gzBytes = gzBytes;
        this.gzSha256 = gzSha256 == null ? null : gzSha256.toLowerCase(Locale.ROOT);
        this.tarBytes = tarBytes;
    }

    /** True when the copied bytes are the ones the companion describes. */
    public boolean matches(long size, String sha256Hex) {
        return gzBytes > 0 && tarBytes > 0 && gzSha256 != null && size == gzBytes
                && sha256Hex != null && gzSha256.equals(sha256Hex.toLowerCase(Locale.ROOT));
    }
}
