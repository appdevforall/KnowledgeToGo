/*
 * ============================================================================
 * Name        : BooksWishlist.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. The persisted "order" of books the user picked in the wizard, before
 *               the system (and Calibre-Web) exist. Stored in app-private SharedPreferences as a
 *               JSON array of {id, title, url}, so it survives the system install. After install,
 *               the provisioning step drains this into BooksDownloadService (which already takes
 *               id/title/url) and clears it. Kept minimal on purpose: the URL travels with the
 *               order so provisioning never depends on the server catalog version.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class BooksWishlist {
    private BooksWishlist() {}

    private static final String PREFS = "k2go_books_wishlist";
    private static final String KEY = "order";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The current order as a JSON array of {id, title, url}. Never null. */
    public static JSONArray all(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY, "[]");
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static int size(Context ctx) { return all(ctx).length(); }

    public static boolean contains(Context ctx, String id) {
        if (id == null) return false;
        JSONArray a = all(ctx);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return true;
        }
        return false;
    }

    /** Add a book (no-op if already present). */
    public static void add(Context ctx, String id, String title, String url) {
        if (id == null || id.isEmpty() || contains(ctx, id)) return;
        JSONArray a = all(ctx);
        try {
            a.put(new JSONObject().put("id", id).put("title", title == null ? "" : title)
                    .put("url", url == null ? "" : url));
            save(ctx, a);
        } catch (Exception ignored) {}
    }

    public static void remove(Context ctx, String id) {
        if (id == null) return;
        JSONArray a = all(ctx);
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) out.put(o);
        }
        save(ctx, out);
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    private static void save(Context ctx, JSONArray a) {
        prefs(ctx).edit().putString(KEY, a.toString()).apply();
    }
}
