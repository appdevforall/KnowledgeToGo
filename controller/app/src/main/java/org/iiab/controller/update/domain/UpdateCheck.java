/*
 * ============================================================================
 * Name        : UpdateCheck.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Domain rule for the OTA self-updater: is the server build newer
 *               than the installed one? Pure JVM, unit-testable.
 * ============================================================================
 */
package org.iiab.controller.update.domain;

/**
 * Pure (framework-free) rules for the in-app OTA updater.
 *
 * <p>The server publishes a {@code versionCodeBase}; the installed app's raw
 * {@code versionCode} maps to the same base by integer division by 10 (the
 * project's per-ABI versioning scheme). An update is offered only when the
 * server base is strictly greater.
 */
public final class UpdateCheck {

    /** The project encodes the ABI in the last digit of the versionCode. */
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
