package org.iiab.controller.redesign;

import android.os.Environment;

/** Device storage in GB, from the app data partition (matches the old PlannerController source). */
public final class StorageInfo {
    private StorageInfo() { }
    private static final double GB = 1024d * 1024d * 1024d;

    public static double totalGb() { return Environment.getDataDirectory().getTotalSpace() / GB; }
    public static double freeGb() { return Environment.getDataDirectory().getFreeSpace() / GB; }
    public static double usedGb() { return Math.max(0, totalGb() - freeGb()); }
}
