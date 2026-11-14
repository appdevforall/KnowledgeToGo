/*
 * ============================================================================
 * Name        : PlatformInfoReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads PlatformInfo from the one source of each fact. Does a small file
 *               read (the library ABI), so call it off the main thread.
 * ============================================================================
 */
package org.appdevforall.k2go.deviceinfo.data;

import android.content.Context;
import android.os.Build;

import org.appdevforall.k2go.SystemStateEvaluator;
import org.appdevforall.k2go.arch.data.AbiSource;
import org.appdevforall.k2go.arch.data.InstalledLibraryAbi;
import org.appdevforall.k2go.deviceinfo.domain.GetDeviceArchUseCase;
import org.appdevforall.k2go.deviceinfo.domain.PlatformInfo;

import java.io.File;

public final class PlatformInfoReader {

    private PlatformInfoReader() {}

    public static PlatformInfo read(Context ctx) {
        File root = SystemStateEvaluator.rootfsDir(ctx);
        boolean tree = AbiSource.libraryTreePresent(root);
        return new PlatformInfo(
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                new GetDeviceArchUseCase(new BuildDeviceAbiProvider()).execute(),
                AbiSource.phoneRuns32Bit(),
                AbiSource.appAbi(),
                tree, tree ? InstalledLibraryAbi.read(root) : null);
    }
}
