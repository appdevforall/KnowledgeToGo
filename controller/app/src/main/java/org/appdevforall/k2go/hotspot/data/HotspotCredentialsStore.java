/*
 * ============================================================================
 * Name        : HotspotCredentialsStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-127. Persists the Wi-Fi Direct hotspot name suffix and password in
 *               SharedPreferences. On first use it writes the factory defaults (option 6-B): a
 *               stable per-device 3-digit name suffix (so two nearby K2Go hotspots do not
 *               collide) and the shared default password (known, so an unedited hotspot just
 *               works). The owner can change both later (PR B); the saved values then persist
 *               across restarts until changed again. The pure rules live in the domain
 *               HotspotCredentials; this class only stores and resolves.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.appdevforall.k2go.hotspot.domain.HotspotCredentials;

import java.util.Random;

public final class HotspotCredentialsStore {

    private static final String PREFS = "k2go_hotspot_creds";
    private static final String KEY_SUFFIX = "name_suffix";
    private static final String KEY_PASS = "password";

    /** The resolved name (full "DIRECT-K2GO-###") and passphrase. */
    public static final class Creds {
        public final String name;
        public final String passphrase;
        Creds(String name, String passphrase) { this.name = name; this.passphrase = passphrase; }
    }

    private final SharedPreferences prefs;

    public HotspotCredentialsStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The current name + password, writing and persisting the factory defaults on first use. */
    public Creds resolve() {
        String suffix = prefs.getString(KEY_SUFFIX, null);
        String pass = prefs.getString(KEY_PASS, null);
        if (suffix == null) {
            suffix = HotspotCredentials.defaultSuffix(new Random());
            prefs.edit().putString(KEY_SUFFIX, suffix).apply();
        }
        if (pass == null) {
            pass = HotspotCredentials.DEFAULT_PASSWORD;
            prefs.edit().putString(KEY_PASS, pass).apply();
        }
        return new Creds(HotspotCredentials.name(suffix), pass);
    }

    /** Saves the owner's edited values (PR B). A null argument keeps the current stored value. */
    public void save(String nameSuffix, String passphrase) {
        SharedPreferences.Editor e = prefs.edit();
        if (nameSuffix != null) e.putString(KEY_SUFFIX, nameSuffix.trim());
        if (passphrase != null) e.putString(KEY_PASS, passphrase);
        e.apply();
    }
}
