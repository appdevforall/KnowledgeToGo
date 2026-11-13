/*
 * ============================================================================
 * Name        : UpdateCheck.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Domain rule for the OTA self-updater: is the server build newer
 *               than the installed one? Pure JVM, unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.update.domain;

/**
 * Pure (framework-free) rules for the in-app OTA updater.
 *
 * <p>The server publishes a {@code versionCodeBase}; the installed app's raw
 * {@code versionCode} is that base x 10 (build.gradle), so integer division by 10
 * maps it back. An update is offered only when the server base is strictly greater.
 */
public final class UpdateCheck {

    /**
     * The APK versionCode is base x 10. Up to v1.1 the last digit named the ABI split
     * (0/1/2); since K2GO-565 it is always 0, kept so that v1.1 split installs update.
     */
    public static final int VERSION_CODE_ABI_FACTOR = 10;

    private UpdateCheck() {
        // Static utility; not instantiable.
    }

    /** The shared base of a raw per-ABI {@code versionCode}. */
    public static int baseOf(int rawVersionCode) {
        return rawVersionCode / VERSION_CODE_ABI_FACTOR;
    }

    /** True if the server build (already a base) is newer than the installed raw code. */
    public static boolean isUpdateAvailable(int serverVersionCodeBase, int installedRawVersionCode) {
        return serverVersionCodeBase > baseOf(installedRawVersionCode);
    }
}
