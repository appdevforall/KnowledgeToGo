/*
 * ============================================================================
 * Name        : ApkAbiReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads the native ABIs that an APK carries (its lib/<abi>/
 *               folders), so the shared file is named after what it contains,
 *               not after the phone that sends it (ADFA-4540, K2GO-510).
 *               JDK zip only, so it is testable on a plain JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.data;

import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkAbiReader {

    private ApkAbiReader() {
    }

    /** Distinct ABI folder names under {@code lib/} in the APK; empty when it has no native libs. */
    public static Set<String> abis(String apkPath) throws IOException {
        Set<String> abis = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                int slash = name.indexOf('/', 4);
                if (name.startsWith("lib/") && slash > 4) {
                    abis.add(name.substring(4, slash));
                }
            }
        }
        return abis;
    }
}
