/*
 * ============================================================================
 * Name        : HiddenModules.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4958. Modules the user hid from Home ("Hide"). Modules can't be uninstalled;
 *               Hide only declutters Home, and Restore (Module management) brings them back.
 *               Persisted as comma-joined yamlBaseKeys, mirrors ModuleWishlist.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.LinkedHashSet;

public final class HiddenModules {

    private HiddenModules() {}

    private static final String PREFS = "k2go_hidden_modules";
    private static final String KEY = "modules";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static LinkedHashSet<String> read(Context ctx) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw != null && !raw.isEmpty()) {
            for (String k : raw.split(",")) { k = k.trim(); if (!k.isEmpty()) set.add(k); }
        }
        return set;
    }

    private static void write(Context ctx, LinkedHashSet<String> set) {
        prefs(ctx).edit().putString(KEY, TextUtils.join(",", set)).apply();
    }

    public static void add(Context ctx, String key) {
        if (key == null) return;
        LinkedHashSet<String> s = read(ctx);
        if (s.add(key)) write(ctx, s);
    }

    public static void remove(Context ctx, String key) {
        LinkedHashSet<String> s = read(ctx);
        if (s.remove(key)) write(ctx, s);
    }

    public static boolean contains(Context ctx, String key) {
        return key != null && read(ctx).contains(key);
    }

    public static boolean isEmpty(Context ctx) { return read(ctx).isEmpty(); }

    public static String[] keys(Context ctx) {
        LinkedHashSet<String> s = read(ctx);
        return s.toArray(new String[0]);
    }
}
