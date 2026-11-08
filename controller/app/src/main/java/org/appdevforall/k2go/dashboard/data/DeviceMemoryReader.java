/*
 * ============================================================================
 * Name        : DeviceMemoryReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-557. Reads live device memory into a domain DeviceMemory, from
 *               ActivityManager.MemoryInfo (device-wide figures, not this process heap). Data layer:
 *               the only place in this slice that touches android.*.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.data;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.dashboard.domain.DeviceMemory;

public final class DeviceMemoryReader {
    private DeviceMemoryReader() {}

    /** Snapshot device memory now. If ActivityManager is unavailable, return a snapshot that reads as
     *  "not low", so the advisory never fires on a bad read (fail open: the update is non-blocking). */
    @NonNull
    public static DeviceMemory read(@NonNull Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return new DeviceMemory(Long.MAX_VALUE, 0L, false);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return new DeviceMemory(info.availMem, info.totalMem, info.lowMemory);
    }
}
