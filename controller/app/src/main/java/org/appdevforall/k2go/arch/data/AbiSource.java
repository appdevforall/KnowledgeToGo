/*
 * ============================================================================
 * Name        : AbiSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-571. The one place that answers "what does the app run as" and
 *               "what is the installed library". ADR-K2GO-571.
 * ============================================================================
 */
package org.appdevforall.k2go.arch.data;

import android.content.Context;
import android.os.Build;

import org.appdevforall.k2go.SystemStateEvaluator;
import org.appdevforall.k2go.arch.domain.LibraryArch;

public final class AbiSource {

    private AbiSource() {}

    /** The ABI this app process runs as. Since K2GO-565 the APK carries both ARM ABIs. */
    public static String appAbi() {
        return android.os.Process.is64Bit() ? LibraryArch.ARM64 : LibraryArch.ARM32;
    }

    public static int appBits() {
        return LibraryArch.bitsOf(appAbi());
    }

    /** True when the phone runs 32-bit programs (some 64-bit-only phones do not). */
    public static boolean phoneRuns32Bit() {
        return Build.SUPPORTED_32_BIT_ABIS != null && Build.SUPPORTED_32_BIT_ABIS.length > 0;
    }

    /** The installed library's ABI, or {@code null} when none is installed or it cannot be told. */
    public static String libraryAbi(Context ctx) {
        return InstalledLibraryAbi.read(SystemStateEvaluator.rootfsDir(ctx));
    }

    /** The installed library's ABI, else the app's (the ABI a fresh install gets). */
    public static String libraryOrAppAbi(Context ctx) {
        String abi = libraryAbi(ctx);
        return abi != null ? abi : appAbi();
    }

    /**
     * The library ABI for the module gate: the app's when no library is installed (a fresh install gets
     * it), else the installed one. {@code null} while a tree exists but its ABI cannot be told (a restore
     * or a Clone mid-way), so a 64-bit-only module stays hidden.
     */
    public static String moduleAbi(Context ctx) {
        java.io.File root = SystemStateEvaluator.rootfsDir(ctx);
        return root.isDirectory() ? InstalledLibraryAbi.read(root) : appAbi();
    }

    /** {@link LibraryArch#canRun(String, String, boolean)} for this app on this phone. */
    public static boolean canRun(String libraryAbi) {
        return LibraryArch.canRun(libraryAbi, appAbi(), phoneRuns32Bit());
    }

    /** The same rule for a width (the Clone QR carries bits). */
    public static boolean canRunBits(int libraryBits) {
        return LibraryArch.canRun(libraryBits, appBits(), phoneRuns32Bit());
    }
}
