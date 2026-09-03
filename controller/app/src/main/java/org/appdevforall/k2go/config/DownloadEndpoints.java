/*
 * ============================================================================
 * Name        : DownloadEndpoints.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5368. One owner for "where content comes from". The two
 *               buckets are DELIBERATELY separate constants, not one host with
 *               paths: the APK repo and the rootfs store are different buckets
 *               with different lifecycles, and collapsing them would make the
 *               next move of either one a search-and-replace again. Compare
 *               BoxEndpoints, which owns the other end (the box on localhost).
 * ============================================================================
 */
package org.appdevforall.k2go.config;

/**
 * The remote origins this app downloads from.
 *
 * <p>These used to be spelled out at every call site — four literals for the rootfs store and four
 * for the APK repo — so moving a host meant finding all eight, and one of them lives inside a shell
 * script this app generates as text ({@code TerminalController}), where a missed edit is invisible to
 * the compiler and only fails on a device. They have one home now.
 *
 * <p><b>What this does NOT own:</b> the URLs the rootfs tarball is actually fetched from. The app
 * asks {@link #ROOTFS_STORE} for {@code latest_<tier>_<arch>.meta4}, but the download sources live
 * <em>inside</em> that metalink, written at build time by {@code tools/rootfs-builder}
 * ({@code PUBLISH_URL} + {@code MIRRORS}). Pointing this constant at a new bucket does not move the
 * big download; the two have to travel together.
 */
public final class DownloadEndpoints {

    private DownloadEndpoints() {
    }

    /** Bucket {@code k2go-rootfs}: the rootfs metalinks and the proot-distro Debian bases. Serves
     *  from its root — the old host kept everything under {@code /android/rootfs}. */
    public static final String ROOTFS_STORE = "https://pub-d64c885cef6c42db8c7925144d73d0ee.r2.dev";

    /** Bucket {@code k2go-apk-repo}: the OTA manifest and the APKs, plus the Kolibri catalogs. */
    public static final String APK_REPO = "https://k2go-download.appdevforall.org";

    /** The proot-distro release whose Debian base this app installs. Kept here because it is part of
     *  the path, and the builder pins the same version ({@code PD_VERSION}). */
    public static final String PROOT_DISTRO_VERSION = "4.29.0";

    /** Where the Debian base tarballs for {@link #PROOT_DISTRO_VERSION} live. */
    public static String prootDistroBase() {
        return ROOTFS_STORE + "/proot-distro-v" + PROOT_DISTRO_VERSION + "/";
    }
}
