/*
 * ============================================================================
 * Name        : CertDigests.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Domain rule: does a downloaded APK's set of signing-certificate
 *               digests match the running app's? Pure JVM, unit-testable.
 * ============================================================================
 */
package org.iiab.controller.update.domain;

import java.util.Set;

/**
 * Pure comparison of APK signing-certificate digests.
 *
 * <p>The OTA updater must only install an APK signed by the <em>same</em>
 * certificate as the running app (Android enforces this for updates anyway;
 * checking it ourselves before launching the installer rejects MITM/tampered
 * or non-APK downloads early with a clear message — see tech-debt F15).
 *
 * <p>Certificate extraction (Android {@code PackageManager}) lives in the data
 * layer; this only compares the resulting digest sets, so it is unit-testable
 * on a plain JVM.
 */
public final class CertDigests {

    private CertDigests() {
        // Static utility; not instantiable.
    }

    /**
     * True only if both sets are non-empty and identical. An empty candidate set
     * (e.g. the download was not a validly-signed APK — a text/HTML error page)
     * therefore fails, as does any set signed by a different certificate.
     */
    public static boolean sameSigner(Set<String> appDigests, Set<String> apkDigests) {
        if (appDigests == null || apkDigests == null) {
            return false;
        }
        if (appDigests.isEmpty() || apkDigests.isEmpty()) {
            return false;
        }
        return appDigests.equals(apkDigests);
    }
}
