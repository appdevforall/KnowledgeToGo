/*
 * ============================================================================
 * Name        : DeviceMemory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-557. A snapshot of device memory at one moment, as plain numbers. Pure domain:
 *               no android.* import, so the low-memory rule that reads it stays unit-testable on a
 *               plain JVM. The data source (DeviceMemoryReader) fills this from ActivityManager.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.domain;

public final class DeviceMemory {
    private final long availableBytes;
    private final long totalBytes;
    private final boolean systemLowMemory;

    public DeviceMemory(long availableBytes, long totalBytes, boolean systemLowMemory) {
        this.availableBytes = availableBytes;
        this.totalBytes = totalBytes;
        this.systemLowMemory = systemLowMemory;
    }

    public long availableBytes() { return availableBytes; }

    public long totalBytes() { return totalBytes; }

    /** True when Android already reports the system is under memory pressure (MemoryInfo.lowMemory). */
    public boolean systemLowMemory() { return systemLowMemory; }
}
