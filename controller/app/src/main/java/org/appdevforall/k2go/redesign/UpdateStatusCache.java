/*
 * ============================================================================
 * Name        : UpdateStatusCache.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5026. Last-known dash-node update status, so the card can still show "Up to date"
 *               / "Update available" when a fresh check can't run (box stopped or offline) instead of
 *               a blank/"checking" pill. Written whenever a live /system/dashboard/update-check
 *               succeeds; read as the fallback when it fails. Tiny SharedPreferences store.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.SharedPreferences;

public final class UpdateStatusCache {
    private UpdateStatusCache() {}

    private static final String PREFS = "k2go_dash_update";
    private static final String KEY_HAS = "has_value";
    private static final String KEY_UPDATE_AVAILABLE = "update_available";
    // ADFA-5051 follow-up: the dashboard version the WebView cache was last cleared for. When the
    // installed dash-node version changes (a rebuild), its served JS/HTML changed under the WebView,
    // so we clear the cache once and record the new version here.
    private static final String KEY_CACHE_VER = "cache_cleared_version";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Persist the outcome of a successful live check. */
    public static void save(Context ctx, boolean updateAvailable) {
        prefs(ctx).edit()
                .putBoolean(KEY_HAS, true)
                .putBoolean(KEY_UPDATE_AVAILABLE, updateAvailable)
                .apply();
    }

    /** True once at least one live check has succeeded (so a cached state exists to show). */
    public static boolean has(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HAS, false);
    }

    /** Last-known "a newer build is available" flag (false when never checked). */
    public static boolean updateAvailable(Context ctx) {
        return prefs(ctx).getBoolean(KEY_UPDATE_AVAILABLE, false);
    }

    /** ADFA-5051 follow-up: the dashboard version the WebView cache was last cleared for (or null). */
    public static String cacheClearedVersion(Context ctx) {
        return prefs(ctx).getString(KEY_CACHE_VER, null);
    }

    /** ADFA-5051 follow-up: record the dashboard version the WebView cache was just cleared for. */
    public static void setCacheClearedVersion(Context ctx, String version) {
        prefs(ctx).edit().putString(KEY_CACHE_VER, version == null ? "" : version).apply();
    }
}
