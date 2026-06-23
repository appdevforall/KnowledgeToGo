/*
 * ============================================================================
 * Name        : PrefsDnsConfigRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : SharedPreferences-backed DnsConfigRepository (new keys; not the vestigial VPN ones).
 * ============================================================================
 */
package org.iiab.controller.network.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.iiab.controller.network.domain.DnsConfig;
import org.iiab.controller.network.domain.DnsConfigRepository;

/**
 * {@link DnsConfigRepository} backed by a dedicated SharedPreferences file. Uses
 * fresh keys (NOT the vestigial VPN-era {@code DnsIpv4}/{@code DnsIpv6} in
 * {@code Preferences}, which this feature will retire). {@code MODE_MULTI_PROCESS}
 * matches the rest of the app.
 */
public final class PrefsDnsConfigRepository implements DnsConfigRepository {

    private static final String PREFS = "NetworkDnsPrefs";
    private static final String K_CUSTOM = "dns.custom.enabled";
    private static final String K_PRIMARY = "dns.primary";
    private static final String K_SECONDARY = "dns.secondary";

    private final SharedPreferences prefs;

    public PrefsDnsConfigRepository(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    @Override
    public DnsConfig loadEffective() {
        return isCustomEnabled() ? loadCustom() : DnsConfig.defaults();
    }

    @Override
    public DnsConfig loadCustom() {
        DnsConfig d = DnsConfig.defaults();
        String primary = prefs.getString(K_PRIMARY, d.primary());
        String secondary = prefs.getString(K_SECONDARY, d.secondary());
        return new DnsConfig(primary, secondary);
    }

    @Override
    public boolean isCustomEnabled() {
        return prefs.getBoolean(K_CUSTOM, false);
    }

    @Override
    public void saveCustom(DnsConfig config) {
        prefs.edit()
                .putBoolean(K_CUSTOM, true)
                .putString(K_PRIMARY, config.primary())
                .putString(K_SECONDARY, config.secondary())
                .commit();
    }

    @Override
    public void disableCustom() {
        prefs.edit().putBoolean(K_CUSTOM, false).commit();
    }
}
