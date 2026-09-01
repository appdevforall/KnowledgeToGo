/*
 * ============================================================================
 * Name        : ZimWishlist.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. The persisted ZIM/Wikipedia "order" chosen in the wizard, before the
 *               system exists. Stored in app-private SharedPreferences as a JSON array of
 *               {key, bytes}, where key is the catalog selector "project|lang|flavour" used by the
 *               ZIM flow (SetupLibraryActivity#getZimCart). Survives the install; after install the
 *               provisioning step resolves each key against assets/kiwix_catalog.csv and drains it
 *               into ZimDownloadService. The catalog itself is already bundled offline, so nothing
 *               else is needed here.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ZimWishlist {
    private ZimWishlist() {}

    private static final String PREFS = "k2go_zim_wishlist";
    private static final String KEY = "order";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The current order as a JSON array of {key, bytes}. Never null. */
    public static JSONArray all(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY, "[]");
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static int size(Context ctx) { return all(ctx).length(); }

    /** Merge a cart ("project|lang|flavour" -> bytes) into the order, de-duped by key. */
    public static void add(Context ctx, Map<String, Long> cart) {
        if (cart == null || cart.isEmpty()) return;
        LinkedHashMap<String, Long> merged = new LinkedHashMap<>();
        JSONArray cur = all(ctx);
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null && !o.optString("key").isEmpty()) merged.put(o.optString("key"), o.optLong("bytes", 0));
        }
        merged.putAll(cart);
        JSONArray out = new JSONArray();
        for (Map.Entry<String, Long> e : merged.entrySet()) {
            try { out.put(new JSONObject().put("key", e.getKey()).put("bytes", e.getValue() == null ? 0 : e.getValue())); }
            catch (Exception ignored) {}
        }
        prefs(ctx).edit().putString(KEY, out.toString()).apply();
    }

    /** ADFA-5169: drop one order by its "project|lang|flavour" key; the rest stay.
     *  No-op if the key is absent. Mirrors BooksWishlist.remove / KolibriWishlist.remove. */
    public static void remove(Context ctx, String key) {
        if (key == null) return;
        JSONArray cur = all(ctx);
        JSONArray out = new JSONArray();
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null && !key.equals(o.optString("key"))) out.put(o);
        }
        prefs(ctx).edit().putString(KEY, out.toString()).apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }
}
