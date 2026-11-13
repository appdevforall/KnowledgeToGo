/*
 * ============================================================================
 * Name        : KiwixLibrarySupport.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-573. Reads from the installed tree whether the library can host
 *               Kiwix. Plain java.io, so it is unit-tested on a temp dir.
 * ============================================================================
 */
package org.appdevforall.k2go.arch.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Two facts, both in the tree: kiwix-serve is installed, or the kiwix role carries patch 0009
 * (tools/upstream-patches), which picks the 32-bit kiwix-tools build. The marker is the role file
 * itself, so it cannot disagree with what an install does; the pre-build contract test
 * (tools/rootfs-builder/tests/kiwix/arch) pins the key. Read on each call, no cache.
 */
public final class KiwixLibrarySupport {

    static final String KIWIX_SERVE = "opt/iiab/kiwix/bin/kiwix-serve";
    static final String ROLE_DEFAULTS = "opt/iiab/iiab/roles/kiwix/defaults/main.yml";
    static final String PATCH_MARKER = "kiwix_dpkg_arch_dict:";
    private static final int MAX_LINES = 500;

    private KiwixLibrarySupport() {}

    /** @param iiabRoot the installed tree ({@code installed-rootfs/iiab}). */
    public static boolean read(File iiabRoot) {
        if (iiabRoot == null || !iiabRoot.isDirectory()) return false;
        return new File(iiabRoot, KIWIX_SERVE).isFile() || hasPatchMarker(new File(iiabRoot, ROLE_DEFAULTS));
    }

    private static boolean hasPatchMarker(File defaults) {
        if (!defaults.isFile()) return false;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(defaults), StandardCharsets.UTF_8))) {
            String line;
            for (int n = 0; n < MAX_LINES && (line = r.readLine()) != null; n++) {
                if (line.startsWith(PATCH_MARKER)) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
