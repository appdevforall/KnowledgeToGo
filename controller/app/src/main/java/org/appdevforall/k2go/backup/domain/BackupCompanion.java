/*
 * ============================================================================
 * Name        : BackupCompanion.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-570. The facts in X.tar.json, the file a backup writes next to
 *               X.tar.gz. It gives integrity (size and sha-256 of the tarball), not
 *               identity: it is not signed. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.backup.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class BackupCompanion {

    public static final String FORMAT = "k2go-backup/1";
    private static final String TAR_SUFFIX = ".tar.gz";
    private static final String JSON_SUFFIX = ".tar.json";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public final String created;     // ISO-8601 UTC
    public final String appVersion;
    public final String abi;
    public final String tier;        // null when not known
    public final long gzBytes;
    public final String gzSha256;    // lowercase hex
    public final long tarBytes;      // uncompressed stream size

    public BackupCompanion(String created, String appVersion, String abi, String tier,
                           long gzBytes, String gzSha256, long tarBytes) {
        this.created = created;
        this.appVersion = appVersion;
        this.abi = abi;
        this.tier = tier;
        this.gzBytes = gzBytes;
        this.gzSha256 = gzSha256 == null ? null : gzSha256.toLowerCase(Locale.ROOT);
        this.tarBytes = tarBytes;
    }

    /** True when every required field holds a usable value. */
    public boolean isComplete() {
        return notEmpty(created) && notEmpty(appVersion) && notEmpty(abi)
                && gzBytes > 0 && tarBytes > 0
                && gzSha256 != null && SHA256_HEX.matcher(gzSha256).matches();
    }

    /** What this file says about its tarball, for the restore copy check. */
    public org.appdevforall.k2go.deploy.domain.ExpectedArchive expected() {
        return new org.appdevforall.k2go.deploy.domain.ExpectedArchive(gzBytes, gzSha256, tarBytes);
    }

    /** {@code X.tar.gz} -> {@code X.tar.json}, else {@code null}. */
    public static String companionName(String tarballName) {
        if (tarballName == null || !tarballName.endsWith(TAR_SUFFIX)
                || tarballName.length() == TAR_SUFFIX.length()) return null;
        return tarballName.substring(0, tarballName.length() - TAR_SUFFIX.length()) + JSON_SUFFIX;
    }

    /** {@code X.tar.json} -> {@code X.tar.gz}, else {@code null}. */
    public static String tarballName(String companionName) {
        if (companionName == null || !companionName.endsWith(JSON_SUFFIX)
                || companionName.length() == JSON_SUFFIX.length()) return null;
        return companionName.substring(0, companionName.length() - JSON_SUFFIX.length()) + TAR_SUFFIX;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
