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

import java.util.Set;
import java.util.HashSet;

import android.content.Context;
import android.content.SharedPreferences;

public class Preferences {
    public static final String PREFS_NAME = "SocksPrefs";
    public static final String IPV4 = "Ipv4";
    public static final String IPV6 = "Ipv6";
    public static final String GLOBAL = "Global";
    public static final String UDP_IN_TCP = "UdpInTcp";
    public static final String REMOTE_DNS = "RemoteDNS";
    public static final String APPS = "Apps";
    public static final String WATCHDOG_ENABLE = "WatchdogEnable";
    public static final String MAINTENANCE_MODE = "MaintenanceMode";

    private SharedPreferences prefs;

    public Preferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
    }

    public String getMappedDns() {
        return "198.18.0.2";
    }

    public boolean getUdpInTcp() {
        return prefs.getBoolean(UDP_IN_TCP, false);
    }

    public void setUdpInTcp(boolean enable) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(UDP_IN_TCP, enable);
        editor.commit();
    }

    public boolean getRemoteDns() {
        return prefs.getBoolean(REMOTE_DNS, true);
    }

    public void setRemoteDns(boolean enable) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(REMOTE_DNS, enable);
        editor.commit();
    }

    public boolean getIpv4() {
        return prefs.getBoolean(IPV4, true);
    }

    public void setIpv4(boolean enable) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(IPV4, enable);
        editor.commit();
    }

    public boolean getIpv6() {
        return prefs.getBoolean(IPV6, true);
    }

    public void setIpv6(boolean enable) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(IPV6, enable);
        editor.commit();
    }

    public boolean getGlobal() {
        return prefs.getBoolean(GLOBAL, false);
    }

    public void setGlobal(boolean enable) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(GLOBAL, enable);
        editor.commit();
    }

    public Set<String> getApps() {
        return prefs.getStringSet(APPS, new HashSet<String>());
    }

    public void setApps(Set<String> apps) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(APPS, apps);
        editor.commit();
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

    public int getTunnelMtu() {
        return 8500;
    }

    public String getTunnelIpv4Address() {
        return "198.18.0.1";
    }

    public int getTunnelIpv4Prefix() {
        return 32;
    }

    public String getTunnelIpv6Address() {
        return "fc00::1";
    }

    public int getTunnelIpv6Prefix() {
        return 128;
    }

    public int getTaskStackSize() {
        return 81920;
    }
}
