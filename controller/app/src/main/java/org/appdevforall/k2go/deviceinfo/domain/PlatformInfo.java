/*
 * ============================================================================
 * Name        : PlatformInfo.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The three architectures a support case needs (phone, app, library)
 *               plus the Android version. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.deviceinfo.domain;

/**
 * Since K2GO-565 the phone, the app process and the installed library can each have a different
 * width (e.g. a 64-bit phone, a 64-bit app and a 32-bit library). Each value comes from its own
 * source; nothing here derives one from another (ADR-K2GO-571).
 */
public final class PlatformInfo {

    /** What is known about the installed library. */
    public enum LibraryState { NOT_INSTALLED, KNOWN, UNKNOWN }

    public final String androidRelease;
    public final int sdkInt;
    public final String phoneAbi;
    public final boolean phoneRuns32Bit;
    public final String appAbi;
    public final LibraryState libraryState;
    /** The library ABI id when {@link #libraryState} is KNOWN, else {@code null}. */
    public final String libraryAbi;

    public PlatformInfo(String androidRelease, int sdkInt, String phoneAbi, boolean phoneRuns32Bit,
                        String appAbi, boolean libraryTreePresent, String libraryAbi) {
        this.androidRelease = androidRelease;
        this.sdkInt = sdkInt;
        this.phoneAbi = phoneAbi;
        this.phoneRuns32Bit = phoneRuns32Bit;
        this.appAbi = appAbi;
        this.libraryState = !libraryTreePresent ? LibraryState.NOT_INSTALLED
                : libraryAbi != null ? LibraryState.KNOWN : LibraryState.UNKNOWN;
        this.libraryAbi = this.libraryState == LibraryState.KNOWN ? libraryAbi : null;
    }
}
