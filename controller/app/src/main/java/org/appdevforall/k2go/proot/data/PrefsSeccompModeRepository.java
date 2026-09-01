/*
 * ============================================================================
 * Name        : PrefsSeccompModeRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5362. Remembers how proot must be launched on this device.
 * ============================================================================
 */
package org.appdevforall.k2go.proot.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.appdevforall.k2go.proot.domain.CapabilityKey;
import org.appdevforall.k2go.proot.domain.SeccompMode;

/**
 * The one place that remembers whether this device's kernel can run proot with seccomp.
 *
 * <p><b>Lifecycle.</b> Nothing is stored until a launch actually aborts, so the default is always
 * {@link SeccompMode#FILTER} — the fast path — and a launch killed mid-flight leaves no verdict
 * behind to be wrong about. What is stored is tied to a <em>capability key</em>: the OS build, the
 * kernel and the app version. Any of the three changing means the answer may no longer hold (a
 * kernel that gets fixed by an update, a different proot shipped in a new APK), so a key mismatch
 * reads as "not known yet" and the next launch learns again. That is also why nothing ever needs to
 * clear this: the key does it.
 */
public final class PrefsSeccompModeRepository {

    private static final String PREFS = "ProotCapabilityPrefs";
    private static final String K_KEY = "seccomp.key";
    private static final String K_DISABLED = "seccomp.disabled";

    private final SharedPreferences prefs;

    public PrefsSeccompModeRepository(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    /** How proot must be launched here; {@link SeccompMode#FILTER} until proved otherwise. */
    public SeccompMode load() {
        if (!CapabilityKey.holds(prefs.getString(K_KEY, null), capabilityKey())) {
            return SeccompMode.FILTER;   // never learned, or learned about a different OS/app
        }
        return prefs.getBoolean(K_DISABLED, false) ? SeccompMode.DISABLED : SeccompMode.FILTER;
    }

    /**
     * Record what this device needs. Written with {@code commit()}: the very next thing that happens
     * is a relaunch, and on an ungraceful kill an unwritten verdict costs another aborted launch.
     */
    public void remember(SeccompMode mode) {
        prefs.edit()
                .putString(K_KEY, capabilityKey())
                .putBoolean(K_DISABLED, mode == SeccompMode.DISABLED)
                .commit();
    }

    /** This device's key, read off the platform; {@link CapabilityKey} owns what it means. */
    private static String capabilityKey() {
        return CapabilityKey.of(
                Build.FINGERPRINT,
                System.getProperty("os.version"),
                org.appdevforall.k2go.BuildConfig.VERSION_CODE);
    }
}
