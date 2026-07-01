/*
 * ============================================================================
 * Name        : PhantomProcessHelper.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4496. Detects whether Android's phantom-process monitor is
 *               likely active (so a long-running native rsync child could be
 *               SIGKILLed) and opens Developer options so the user can toggle
 *               "Disable child process restrictions" (Android 14+). Used by the
 *               informed pre-flight before starting a Share; the reactive
 *               counterpart is the SIGKILL(137) detection in RsyncManager.
 * ============================================================================
 */
package org.iiab.controller.sync.transport;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

public final class PhantomProcessHelper {

    private static final String TAG = "IIAB-Phantom";
    private static final String FLAG = "settings_enable_monitor_phantom_procs";

    private PhantomProcessHelper() {
    }

    /**
     * Best-effort check: is the phantom-process monitor likely active? Reads the global
     * settings_enable_monitor_phantom_procs flag (the one the Developer-options toggle and the ADB
     * command set). Only meaningful on Android 12+ (API 31). On error we assume active — the
     * pre-flight only warns and the user can continue, so a false positive is harmless.
     */
    public static boolean isMonitoringLikelyActive(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), FLAG);
            if (v == null) return true; // default-on when unset
            return !(v.equals("0") || v.equalsIgnoreCase("false"));
        } catch (Exception e) {
            Log.w(TAG, "reading " + FLAG + " failed", e);
            return true;
        }
    }

    /** Open Developer options (Android 14+ exposes "Disable child process restrictions" there). */
    public static boolean openDeveloperOptions(Context ctx) {
        if (ctx == null) return false;
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot open Developer options", e);
            return false;
        }
    }
}
