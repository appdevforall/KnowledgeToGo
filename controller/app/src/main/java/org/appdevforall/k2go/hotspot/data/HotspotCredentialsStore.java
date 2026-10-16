/*
 * ============================================================================
 * Name        : HotspotCredentialsStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-127. Persists the Wi-Fi Direct hotspot name and password in
 *               SharedPreferences, with a default-vs-custom model (option 6-B):
 *                 - A stable per-device default name suffix, generated once (so two nearby
 *                   K2Go hotspots do not collide), paired with the shared default password
 *                   (known, so an unedited hotspot just works).
 *                 - When the owner turns on a custom name/password, those are stored and used
 *                   instead, and persist across restarts until the owner reverts to the default.
 *               resolve() returns the EFFECTIVE credentials the hotspot should use. The default
 *               suffix is kept separate from any custom value so reverting always restores the
 *               same default name. Pure rules (validation, defaults) live in domain
 *               HotspotCredentials; this class only stores and resolves.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.appdevforall.k2go.hotspot.domain.HotspotBand;
import org.appdevforall.k2go.hotspot.domain.HotspotCredentials;

import java.util.Random;

public final class HotspotCredentialsStore {

    private static final String PREFS = "k2go_hotspot_creds";
    private static final String KEY_DEFAULT_SUFFIX = "default_suffix";   // stable per-device default name
    private static final String KEY_CUSTOM = "custom";                   // owner uses a custom name/password?
    private static final String KEY_CUSTOM_SUFFIX = "custom_suffix";
    private static final String KEY_CUSTOM_PASS = "custom_pass";

    /** The resolved full name ("DIRECT-K2GO-###") and passphrase the hotspot should use. */
    public static final class Creds {
        public final String name;
        public final String passphrase;
        Creds(String name, String passphrase) { this.name = name; this.passphrase = passphrase; }
    }

    private final SharedPreferences prefs;

    public HotspotCredentialsStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The EFFECTIVE credentials: the owner's custom values when set, otherwise the factory default. */
    public Creds resolve() {
        if (isCustom()) {
            String s = prefs.getString(KEY_CUSTOM_SUFFIX, defaultSuffix());
            String p = prefs.getString(KEY_CUSTOM_PASS, HotspotCredentials.DEFAULT_PASSWORD);
            return new Creds(HotspotCredentials.name(s), p);
        }
        return new Creds(HotspotCredentials.name(defaultSuffix()), HotspotCredentials.DEFAULT_PASSWORD);
    }

    /** The stable per-device default name suffix, generated and persisted on first use. */
    public String defaultSuffix() {
        String s = prefs.getString(KEY_DEFAULT_SUFFIX, null);
        if (s == null) {
            // Migrate an older install that stored the suffix under "name_suffix", so the default
            // hotspot name does not change under the owner; otherwise generate a fresh one.
            s = prefs.getString("name_suffix", null);
            if (s == null) s = HotspotCredentials.defaultSuffix(new Random());
            prefs.edit().putString(KEY_DEFAULT_SUFFIX, s).apply();
        }
        return s;
    }

    public boolean isCustom() { return prefs.getBoolean(KEY_CUSTOM, false); }
    public String customSuffix() { return prefs.getString(KEY_CUSTOM_SUFFIX, ""); }
    public String customPassphrase() { return prefs.getString(KEY_CUSTOM_PASS, ""); }

    /** Save the owner's custom name/password (switch on). The caller validates with HotspotCredentials. */
    public void setCustom(String nameSuffix, String passphrase) {
        prefs.edit()
                .putBoolean(KEY_CUSTOM, true)
                .putString(KEY_CUSTOM_SUFFIX, nameSuffix == null ? "" : nameSuffix.trim())
                .putString(KEY_CUSTOM_PASS, passphrase == null ? "" : passphrase)
                .apply();
    }

    /** Revert to the factory default (switch off): the default name + the shared default password. */
    public void clearCustom() {
        prefs.edit().putBoolean(KEY_CUSTOM, false).apply();
    }

    // Hotspot band choice, persisted by its stable id (see domain HotspotBand). Default 2.4 GHz.
    private static final String KEY_BAND = "band";

    public HotspotBand band() {
        return HotspotBand.fromId(prefs.getInt(KEY_BAND, HotspotBand.DEFAULT.id));
    }
    public void setBand(HotspotBand band) { prefs.edit().putInt(KEY_BAND, band.id).apply(); }
}
