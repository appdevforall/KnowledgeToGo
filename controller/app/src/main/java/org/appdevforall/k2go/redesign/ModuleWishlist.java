/*
 * ============================================================================
 * Name        : ModuleWishlist.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. The set of proot modules the user has scheduled to install from Module
 *               management, before proceeding to the install index. Mirrors MapsWishlist/BooksWishlist
 *               (a persisted, entry-point-agnostic bank), but holds a list of module yamlBaseKeys
 *               (e.g. "kolibri", "calibreweb") instead of one record — module management can queue
 *               several. Keys are validated against ModuleRegistry so only real modules are stored.
 *               ModuleProvisioner drains this to the module-queue engine; cleared once handed off.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.SharedPreferences;

import org.appdevforall.k2go.ModuleRegistry;

import java.util.LinkedHashSet;

public final class ModuleWishlist {
    private ModuleWishlist() {}

    private static final String PREFS = "k2go_module_wishlist";
    private static final String KEY = "modules";   // comma-joined yamlBaseKeys, insertion order

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static LinkedHashSet<String> read(Context ctx) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw != null && !raw.isEmpty()) {
            for (String k : raw.split(",")) {
                if (!k.isEmpty()) set.add(k);
            }
        }
        return set;
    }

    private static void write(Context ctx, LinkedHashSet<String> set) {
        prefs(ctx).edit().putString(KEY, android.text.TextUtils.join(",", set)).apply();
    }

    /** Schedule a module. No-op if the key isn't a real module (ModuleRegistry allowlist). */
    public static void add(Context ctx, String yamlBaseKey) {
        if (yamlBaseKey == null || !ModuleRegistry.validYamlKeys().contains(yamlBaseKey)) return;
        LinkedHashSet<String> set = read(ctx);
        set.add(yamlBaseKey);
        write(ctx, set);
    }

    /** Unschedule a module. */
    public static void remove(Context ctx, String yamlBaseKey) {
        LinkedHashSet<String> set = read(ctx);
        if (set.remove(yamlBaseKey)) write(ctx, set);
    }

    public static boolean contains(Context ctx, String yamlBaseKey) {
        return read(ctx).contains(yamlBaseKey);
    }

    public static boolean has(Context ctx) { return !read(ctx).isEmpty(); }

    public static int size(Context ctx) { return read(ctx).size(); }

    /** The scheduled module keys, in insertion order. */
    public static String[] keys(Context ctx) {
        LinkedHashSet<String> set = read(ctx);
        return set.toArray(new String[0]);
    }

    public static void clear(Context ctx) { prefs(ctx).edit().remove(KEY).apply(); }
}
