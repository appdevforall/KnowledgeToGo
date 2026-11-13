/*
 * ============================================================================
 * Name        : LibraryHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-573. What the installed library can host, as read from its
 *               own tree. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.arch.domain;

/** The facts the module gate needs about the library a module would land in. */
public final class LibraryHost {

    /** {@link LibraryArch#ARM64}, {@link LibraryArch#ARM32}, or {@code null} when it cannot be told. */
    public final String abi;
    /** True when a 32-bit library can host Kiwix: kiwix-serve is installed, or its role carries patch 0009. */
    public final boolean kiwixOn32Bit;

    public LibraryHost(String abi, boolean kiwixOn32Bit) {
        this.abi = abi;
        this.kiwixOn32Bit = kiwixOn32Bit;
    }

    /**
     * No library yet: a fresh install gets the app's ABI. Its tree cannot be read before it exists, so a
     * 32-bit fresh install does not count on Kiwix support; Kiwix shows once the installed tree says so.
     */
    public static LibraryHost freshInstall(String appAbi) {
        return new LibraryHost(appAbi, false);
    }
}
