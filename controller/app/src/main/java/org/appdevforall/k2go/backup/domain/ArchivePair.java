/*
 * ============================================================================
 * Name        : ArchivePair.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-569/570. The file names of a restore pair: X.tar.gz with its
 *               X.tar.json (own backup) or X.tar.gz.minisig (signed official image).
 *               The one place that knows these names. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.backup.domain;

public final class ArchivePair {

    public enum Kind { TARBALL, JSON, SIGNATURE, OTHER }

    private static final String TARBALL = ".tar.gz";
    private static final String JSON = ".tar.json";
    private static final String SIGNATURE = ".tar.gz.minisig";

    private ArchivePair() {}

    public static Kind kind(String name) {
        if (hasBase(name, SIGNATURE)) return Kind.SIGNATURE;
        if (hasBase(name, TARBALL)) return Kind.TARBALL;
        if (hasBase(name, JSON)) return Kind.JSON;
        return Kind.OTHER;
    }

    /** {@code X.tar.gz} -> {@code X.tar.json}, else {@code null}. */
    public static String json(String tarball) {
        return kind(tarball) == Kind.TARBALL ? base(tarball, TARBALL) + JSON : null;
    }

    /** {@code X.tar.gz} -> {@code X.tar.gz.minisig}, else {@code null}. */
    public static String signature(String tarball) {
        return kind(tarball) == Kind.TARBALL ? tarball + ".minisig" : null;
    }

    /** {@code X.tar.json} or {@code X.tar.gz.minisig} -> {@code X.tar.gz}, else {@code null}. */
    public static String tarball(String companion) {
        switch (kind(companion)) {
            case JSON: return base(companion, JSON) + TARBALL;
            case SIGNATURE: return base(companion, SIGNATURE) + TARBALL;
            default: return null;
        }
    }

    private static boolean hasBase(String name, String suffix) {
        return name != null && name.endsWith(suffix) && name.length() > suffix.length();
    }

    private static String base(String name, String suffix) {
        return name.substring(0, name.length() - suffix.length());
    }
}
