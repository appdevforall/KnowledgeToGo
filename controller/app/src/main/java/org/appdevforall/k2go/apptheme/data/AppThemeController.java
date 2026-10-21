/*
 * ============================================================================
 * Name        : AppThemeController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-478. The one owner of the user's light/dark/system choice:
 *               stores it and applies it, in Settings and at process start.
 * ============================================================================
 */
package org.appdevforall.k2go.apptheme.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import org.appdevforall.k2go.R;

/**
 * Unlike locales, AppCompat does not persist the night mode, so it is stored here and
 * re-applied from {@code Application.onCreate} before any activity is created.
 */
public final class AppThemeController {

    private static final String KEY = "k2go_theme";

    private AppThemeController() {
    }

    /** The saved mode: MODE_NIGHT_NO, MODE_NIGHT_YES or MODE_NIGHT_FOLLOW_SYSTEM (the default). */
    public static int current(Context ctx) {
        int mode = prefs(ctx).getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        return mode == AppCompatDelegate.MODE_NIGHT_NO || mode == AppCompatDelegate.MODE_NIGHT_YES
                ? mode : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /** Saves the choice and applies it to the running activities. */
    public static void apply(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY, mode).apply();
        AppCompatDelegate.setDefaultNightMode(current(ctx));
    }

    /** Re-applies the saved choice. Call once at process start. */
    public static void restore(Context ctx) {
        AppCompatDelegate.setDefaultNightMode(current(ctx));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(
                ctx.getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
    }
}
