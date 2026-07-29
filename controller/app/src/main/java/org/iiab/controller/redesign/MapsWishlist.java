/*
 * ============================================================================
 * Name        : MapsWishlist.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4900. The persisted maps per-layer selection chosen in the wizard, before the
 *               system exists. Mirrors ZimWishlist/BooksWishlist: the wizard cannot run runrole
 *               (no rootfs yet), so it banks the selection here and MapsProvisioner applies it
 *               after the system is installed. Unlike ZIM/Books (a list of items), maps is a single
 *               selection (base / satellite / terrain / search), so this stores one record.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.SharedPreferences;

public final class MapsWishlist {
    private MapsWishlist() {}

    private static final String PREFS = "k2go_maps_wishlist";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Bank the wizard selection. Layer keys use the maps role's values; satellite/terrain "none"
     *  = off, and {@code search} = the static pop-1k-cities engine on/off. {@code mb} is the total
     *  download size of the selection (from the maps catalog), used by the Get More storage
     *  projection so "Your picks" reflects the real maps size. */
    public static void save(Context ctx, String base, String sat, String terrain, boolean search, long mb) {
        prefs(ctx).edit()
                .putBoolean("has", true)
                .putString("base", base == null ? "osm-z11" : base)
                .putString("sat", sat == null ? "none" : sat)
                .putString("terrain", terrain == null ? "none" : terrain)
                .putBoolean("search", search)
                .putLong("mb", Math.max(0, mb))
                .apply();
    }

    public static boolean has(Context ctx) { return prefs(ctx).getBoolean("has", false); }
    public static String base(Context ctx) { return prefs(ctx).getString("base", "osm-z11"); }
    public static String sat(Context ctx) { return prefs(ctx).getString("sat", "none"); }
    public static String terrain(Context ctx) { return prefs(ctx).getString("terrain", "none"); }
    public static boolean search(Context ctx) { return prefs(ctx).getBoolean("search", false); }
    /** Total download size (MB) of the banked selection; 0 if unknown. */
    public static long mb(Context ctx) { return prefs(ctx).getLong("mb", 0); }

    public static void clear(Context ctx) { prefs(ctx).edit().clear().apply(); }
}
