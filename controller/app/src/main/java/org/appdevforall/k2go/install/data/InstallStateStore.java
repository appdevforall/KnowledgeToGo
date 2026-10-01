/*
 * ============================================================================
 * Name        : InstallStateStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-436 (slice 2). The single home for InstallService's SharedPreferences: the
 *               recorded installed tier (in the app internal prefs) and the module-batch queue (in
 *               iiab_queue_prefs). Keeps the file names, the keys and the commit-vs-apply choice in one
 *               place so a reader and a writer cannot drift (one source per fact). The abandoned-install
 *               marker is NOT here: InstallGuard already owns it. Data layer: Android I/O, no decisions,
 *               verified on device. Slice 2 of controller/docs/ADR-installservice-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.install.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.appdevforall.k2go.R;

import java.util.ArrayList;
import java.util.Collection;

public final class InstallStateStore {

    /** The module-batch queue prefs file (separate from the app internal prefs). */
    private static final String QUEUE_PREFS = "iiab_queue_prefs";

    /** The installed tier lives in the app internal prefs file (R.string.pref_file_internal). */
    private static final String KEY_INSTALLED_TIER = "installed_tier";
    private static final String KEY_PENDING_MODULES = "pending_modules";
    private static final String KEY_BATCH_INSTALLING = "is_batch_installing";
    private static final String KEY_MODULE_STATE_TRUSTED = "is_module_state_trusted";

    private InstallStateStore() {
    }

    private static SharedPreferences internal(Context ctx) {
        return ctx.getSharedPreferences(ctx.getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
    }

    private static SharedPreferences queuePrefs(Context ctx) {
        return ctx.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE);
    }

    // ---- installed tier: written at install, read by "Get more" sizing, cleared on abandonment ----

    /** Record the tier being installed so a later content-only "Get more" can size correctly. */
    public static void writeInstalledTier(Context ctx, String tierName) {
        internal(ctx).edit().putString(KEY_INSTALLED_TIER, tierName).apply();
    }

    /** The recorded tier name, or {@code defaultName} when none is set. */
    public static String readInstalledTier(Context ctx, String defaultName) {
        return internal(ctx).getString(KEY_INSTALLED_TIER, defaultName);
    }

    /**
     * Clear the recorded tier. commit(), not apply(): the abandonment path posts UI state right after
     * and an asynchronous write would race it (see InstallService.forgetTheAbandonedSystem).
     */
    public static void clearInstalledTier(Context ctx) {
        internal(ctx).edit().remove(KEY_INSTALLED_TIER).commit();
    }

    // ---- module batch queue (iiab_queue_prefs) ----

    /** Persist the pending module batch and mark a batch install in progress. */
    public static void saveQueue(Context ctx, Collection<String> modules) {
        queuePrefs(ctx).edit()
                .putString(KEY_PENDING_MODULES, TextUtils.join(",", new ArrayList<>(modules)))
                .putBoolean(KEY_BATCH_INSTALLING, true).apply();
    }

    /** Clear the pending batch and mark no batch install in progress. */
    public static void clearQueue(Context ctx) {
        queuePrefs(ctx).edit()
                .putString(KEY_PENDING_MODULES, "")
                .putBoolean(KEY_BATCH_INSTALLING, false).apply();
    }

    /** Mark the on-disk module state no longer trustworthy (a batch was interrupted). */
    public static void markModuleStateUntrusted(Context ctx) {
        queuePrefs(ctx).edit().putBoolean(KEY_MODULE_STATE_TRUSTED, false).apply();
    }
}
