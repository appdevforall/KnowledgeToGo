/*
 * ============================================================================
 * Name        : KolibriWishlist.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The persisted Kolibri "order" chosen in the wizard, before the
 *               system exists (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.iiab.controller.kolibri.domain.ChannelId;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The channels the user picked, banked in app-private storage until there is a
 * server to hand them to.
 *
 * <p>Same shape as {@code ZimWishlist} and {@code BooksWishlist}: a JSON array
 * in a dedicated {@code SharedPreferences} file, app data, so it survives the
 * system install that happens after the wizard.
 *
 * <p>Each entry is:
 * <pre>
 *   { "channelId": "&lt;32 hex&gt;", "version": 9, "name": "Khan Academy",
 *     "bytes": 66876543210, "nodeIds": ["&lt;32 hex&gt;", ...] }
 * </pre>
 *
 * <p>Two fields look redundant and are not:
 * <ul>
 *   <li><b>{@code name}</b> — Kolibri's task API rejects a channel task without
 *       {@code channel_name}; it is a required serializer field. Carrying it
 *       here is also what ADR-4853 decided for Books' {@code download_url}: do
 *       not make provisioning depend on a catalog lookup that can drift between
 *       the pick and the drain.</li>
 *   <li><b>{@code bytes}</b> — so the "will this fit?" figure survives without
 *       re-reading the catalog, and so a channel dropped from Studio between the
 *       pick and the drain still reports a size.</li>
 * </ul>
 *
 * <p>Every read re-parses the stored JSON, so callers on the main thread should
 * read once per pass rather than per use. The underlying {@code SharedPreferences}
 * is memory-cached after the first load, so this is parsing cost rather than disk
 * I/O — cheap for a handful of channels, but not free inside a render loop.
 *
 * <p><b>{@code nodeIds} absent means the whole channel</b>, and it is never
 * written as an empty array. Kolibri reads {@code node_ids: []} as zero nodes and
 * completes successfully having transferred nothing; keeping the empty case
 * unrepresentable here means that silent no-op cannot be stored in the first
 * place. {@code ChannelSelection} enforces the same rule at the domain boundary.
 */
public final class KolibriWishlist {

    private KolibriWishlist() {
    }

    private static final String PREFS = "k2go_kolibri_wishlist";
    private static final String KEY = "order";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The current order. Never null; an empty array when unreadable. */
    public static JSONArray all(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY, "[]");
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static int size(Context ctx) {
        return all(ctx).length();
    }

    /**
     * Adds one channel to the order, replacing any previous entry for it.
     *
     * <p>Later wins, because picking the same channel twice means changing the
     * selection rather than queueing it twice: two concurrent imports of one
     * channel only contend on the same {@code db.sqlite3}.
     *
     * @param nodeIds subtree roots, or null/empty for the whole channel
     */
    public static void add(Context ctx, String channelId, int version, String name,
                           long bytes, List<String> nodeIds) {
        String id = ChannelId.normalise(channelId);
        if (id == null) {
            return;
        }
        LinkedHashMap<String, JSONObject> merged = load(ctx);
        try {
            JSONObject o = new JSONObject()
                    .put("channelId", id)
                    .put("version", Math.max(0, version))
                    .put("name", name == null ? "" : name.trim())
                    .put("bytes", Math.max(0L, bytes));
            JSONArray nodes = normalisedNodes(nodeIds);
            // Absent, never empty: see the class comment.
            if (nodes.length() > 0) {
                o.put("nodeIds", nodes);
            }
            merged.put(id, o);
        } catch (Exception ignored) {
            return;
        }
        save(ctx, merged);
    }

    /** Removes one channel from the order. */
    public static void remove(Context ctx, String channelId) {
        String id = ChannelId.normalise(channelId);
        if (id == null) {
            return;
        }
        LinkedHashMap<String, JSONObject> merged = load(ctx);
        if (merged.remove(id) != null) {
            save(ctx, merged);
        }
    }

    /** True when this channel is already in the order. */
    public static boolean contains(Context ctx, String channelId) {
        String id = ChannelId.normalise(channelId);
        return id != null && load(ctx).containsKey(id);
    }

    /** Sum of the banked sizes, for the storage projection. */
    public static long totalBytes(Context ctx) {
        JSONArray a = all(ctx);
        long total = 0L;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                total += Math.max(0L, o.optLong("bytes", 0L));
            }
        }
        return total;
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    // ---- internals ---------------------------------------------------------

    private static LinkedHashMap<String, JSONObject> load(Context ctx) {
        LinkedHashMap<String, JSONObject> out = new LinkedHashMap<>();
        JSONArray cur = all(ctx);
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = ChannelId.normalise(o.optString("channelId", ""));
            if (id != null) {
                out.put(id, o);
            }
        }
        return out;
    }

    private static void save(Context ctx, LinkedHashMap<String, JSONObject> merged) {
        JSONArray out = new JSONArray();
        for (JSONObject o : merged.values()) {
            out.put(o);
        }
        prefs(ctx).edit().putString(KEY, out.toString()).apply();
    }

    /** Node ids normalised, de-duplicated, order preserved, invalid ones dropped. */
    private static JSONArray normalisedNodes(List<String> nodeIds) {
        JSONArray out = new JSONArray();
        if (nodeIds == null) {
            return out;
        }
        List<String> seen = new ArrayList<>();
        for (String raw : nodeIds) {
            String n = ChannelId.normalise(raw);
            if (n != null && !seen.contains(n)) {
                seen.add(n);
                out.put(n);
            }
        }
        return out;
    }
}
