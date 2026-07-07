/*
 ============================================================================
 Name        : Preferences.java
 Author      : hev <r@hev.cc>
 Contributors: IIAB Project
 Copyright   : Copyright (c) 2023 xyz
 Copyright (c) 2026 IIAB Project
 Description : Preferences
 ============================================================================
 */

package org.iiab.controller;


import android.content.Context;
import android.content.SharedPreferences;

public class Preferences {
    public static final String PREFS_NAME = "SocksPrefs";
    public static final String WATCHDOG_ENABLE = "WatchdogEnable";
    public static final String MAINTENANCE_MODE = "MaintenanceMode";

    private SharedPreferences prefs;

    public Preferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
    }

    public boolean getWatchdogEnable() {
        return prefs.getBoolean(WATCHDOG_ENABLE, false);
    }

    public void setWatchdogEnable(boolean enable) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(WATCHDOG_ENABLE, enable);
        editor.commit();
    }

    public boolean getMaintenanceMode() {
        return prefs.getBoolean(MAINTENANCE_MODE, true);
    }

    public void setMaintenanceMode(boolean enable) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(MAINTENANCE_MODE, enable);
        editor.commit();
    }

    public int getTaskStackSize() {
        return 81920;
    }
}
