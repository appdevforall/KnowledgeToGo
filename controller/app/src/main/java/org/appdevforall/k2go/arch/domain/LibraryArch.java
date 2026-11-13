/*
 * ============================================================================
 * Name        : LibraryArch.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-571. Which library (rootfs) architecture this app can run.
 *               Pure JVM. See controller/docs/ADR-K2GO-571-library-architecture.md.
 * ============================================================================
 */
package org.appdevforall.k2go.arch.domain;

/** The ABI vocabulary of the rootfs identity file, and the rule that joins a library to the app. */
public final class LibraryArch {

    public static final String ARM64 = "arm64-v8a";
    public static final String ARM32 = "armeabi-v7a";

    private LibraryArch() {}

    /** 64 or 32 for a known ABI id, else 0. */
    public static int bitsOf(String abi) {
        if (ARM64.equals(abi)) return 64;
        if (ARM32.equals(abi)) return 32;
        return 0;
    }

    /** The ABI id for 64 or 32 bits, else {@code null}. */
    public static String abiOf(int bits) {
        if (bits == 64) return ARM64;
        if (bits == 32) return ARM32;
        return null;
    }

    /** The Debian architecture of a known ABI id ({@code arm64} or {@code armhf}), else {@code null}. */
    public static String debArchOf(String abi) {
        int bits = bitsOf(abi);
        return bits == 64 ? "arm64" : bits == 32 ? "armhf" : null;
    }

    /**
     * True when an app of {@code appBits} can run a library of {@code libraryBits}: the same width, or a
     * 32-bit library on a 64-bit app when the phone runs 32-bit programs (proot's 32-bit loader).
     * An unknown width (0) is never runnable.
     */
    public static boolean canRun(int libraryBits, int appBits, boolean phoneRuns32Bit) {
        if (libraryBits == 0 || appBits == 0) return false;
        return libraryBits == appBits || (libraryBits == 32 && appBits == 64 && phoneRuns32Bit);
    }

    /**
     * True when a module can run in a library of {@code libraryAbi}. A 64-bit-only module (e.g. Kiwix)
     * needs a 64-bit library; an unknown ABI ({@code null}) fails closed.
     */
    public static boolean supportsModule(boolean requires64Bit, String libraryAbi) {
        return !requires64Bit || bitsOf(libraryAbi) == 64;
    }

    /** {@link #canRun(int, int, boolean)} for ABI ids. */
    public static boolean canRun(String libraryAbi, String appAbi, boolean phoneRuns32Bit) {
        return canRun(bitsOf(libraryAbi), bitsOf(appAbi), phoneRuns32Bit);
    }
}
