/*
 * ============================================================================
 * Name        : RootfsArchive.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Pure heuristics over an archive's entry list: does it look like
 *               a Linux rootfs, and which member is a good ELF binary to probe
 *               for its architecture? Used to gate import/restore.
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

import java.util.Collection;

/**
 * Pure (framework-free) inspection of a tar archive's <em>member names</em>
 * (the listing), used to reject "import a random file / a ZIM as a rootfs" and
 * to choose a member whose bytes can be probed for the ELF architecture.
 *
 * <p>Names may carry prefixes like {@code ./} or {@code installed-rootfs/}
 * (app-created backups are {@code tar -C <root> installed-rootfs}); matching is
 * therefore prefix-tolerant (substring / suffix).
 */
public final class RootfsArchive {

    /** Well-known Debian/Ubuntu binaries that are real ELF files (not scripts/symlinks). */
    private static final String[] PROBE_BINARIES = {
            "bin/dash", "bin/bash", "bin/ls", "bin/cat", "bin/busybox",
            "usr/bin/dpkg", "sbin/init"
    };

    private RootfsArchive() {
        // Static utility; not instantiable.
    }

    private static String norm(String name) {
        if (name == null) {
            return "";
        }
        String n = name.replace('\\', '/').trim();
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        return n;
    }

    /**
     * True if the listing looks like a Linux rootfs: it contains an {@code etc/}
     * directory and a {@code bin/}, {@code sbin/} or {@code usr/} directory
     * (prefix-tolerant). Rejects obviously-non-rootfs archives (e.g. a single
     * {@code .zim} file).
     */
    public static boolean looksLikeRootfs(Collection<String> entryNames) {
        if (entryNames == null) {
            return false;
        }
        boolean hasEtc = false;
        boolean hasBinOrUsr = false;
        for (String raw : entryNames) {
            String n = norm(raw);
            if (n.contains("etc/")) {
                hasEtc = true;
            }
            if (n.contains("bin/") || n.contains("sbin/") || n.contains("usr/")) {
                hasBinOrUsr = true;
            }
            if (hasEtc && hasBinOrUsr) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pick an archive member that should be a real ELF binary, so the caller can
     * extract its header and read the architecture. Returns the original entry
     * name (for {@code tar} extraction), or {@code null} if none was found.
     */
    public static String pickBinaryEntry(Collection<String> entryNames) {
        if (entryNames == null) {
            return null;
        }
        for (String wanted : PROBE_BINARIES) {
            for (String raw : entryNames) {
                String n = norm(raw);
                if (n.equals(wanted) || n.endsWith("/" + wanted)) {
                    return raw;
                }
            }
        }
        return null;
    }
}
