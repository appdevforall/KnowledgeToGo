/*
 * ============================================================================
 * Name        : BooksCatalogAsset.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. OFFLINE Books catalog, read from the bundled asset
 *               assets/books_catalog.jsonl (plain JSONL — a ".gz" double-extension asset got
 *               EOL-normalized and dropped from the APK; single-extension plain text is safe),
 *               (generated from the dashboard catalog.db by
 *               tools/gen-books-catalog-asset.py). This is the wizard/pre-install data source:
 *               the user can search + pick books before the system (and Calibre-Web) exist. It
 *               emits the SAME row shape as the live REST search (gutenberg_id, title, author,
 *               language, download_url), so BooksLandingFragment renders it identically. Parsed
 *               once and cached in memory. "Educational" isn't available offline (no bookshelves
 *               in the trimmed asset) — offline browse is Popular + text search + language filter.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.iiab.controller.util.AppExecutors;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BooksCatalogAsset {
    private BooksCatalogAsset() {}

    private static final String TAG = "K2Go-BooksAsset";
    private static final String ASSET = "books_catalog.jsonl";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Parsed once, kept in memory (already popularity-ordered by the generator).
    private static volatile List<JSONObject> CACHE;

    /** Search the offline catalog. lang: ""=all or an ISO code; q empty => popularity order.
     *  ADFA-5329: offset skips earlier batches so the caller can page through with "Load more". */
    public static void search(Context ctx, String q, String lang, int offset, int limit, BooksClient.ArrayCb cb) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            try {
                List<JSONObject> all = ensureLoaded(app);
                String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
                String lc = lang == null ? "" : lang.trim();
                int skip = Math.max(0, offset);
                JSONArray out = new JSONArray();
                int matched = 0, taken = 0;
                for (JSONObject b : all) {
                    if (!lc.isEmpty() && !lc.equalsIgnoreCase(b.optString("language"))) continue;
                    if (!term.isEmpty()) {
                        String hay = (b.optString("title") + " " + b.optString("author")).toLowerCase(Locale.ROOT);
                        if (!hay.contains(term)) continue;
                    }
                    if (matched++ < skip) continue;   // already shown in an earlier batch
                    out.put(b);
                    if (++taken >= Math.max(1, limit)) break;
                }
                MAIN.post(() -> cb.onOk(out));
            } catch (Exception e) {
                Log.w(TAG, "offline catalog read failed for asset '" + ASSET + "'", e);
                MAIN.post(() -> cb.onErr("couldn't read the offline catalog"));
            }
        });
    }

    /** Distinct languages present in the asset (rows {code, count}), most-stocked first. */
    public static void languages(Context ctx, BooksClient.ArrayCb cb) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            try {
                List<JSONObject> all = ensureLoaded(app);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (JSONObject b : all) {
                    String c = b.optString("language", "").trim();
                    if (c.isEmpty()) continue;
                    counts.merge(c, 1, Integer::sum);
                }
                List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
                entries.sort((a, b) -> b.getValue() - a.getValue());
                JSONArray out = new JSONArray();
                for (Map.Entry<String, Integer> e : entries) {
                    out.put(new JSONObject().put("code", e.getKey()).put("count", e.getValue()));
                }
                MAIN.post(() -> cb.onOk(out));
            } catch (Exception e) {
                Log.w(TAG, "offline catalog read failed for asset '" + ASSET + "'", e);
                MAIN.post(() -> cb.onErr("couldn't read the offline catalog"));
            }
        });
    }

    private static synchronized List<JSONObject> ensureLoaded(Context app) throws Exception {
        if (CACHE != null) return CACHE;
        List<JSONObject> list = new ArrayList<>();
        try (InputStream raw = app.getAssets().open(ASSET);
             BufferedReader r = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject j = new JSONObject(line);
                // Map the compact asset keys to the live-REST row shape so the UI is source-agnostic.
                JSONObject row = new JSONObject();
                row.put("gutenberg_id", j.opt("id"));
                row.put("title", j.optString("title", ""));
                row.put("author", j.optString("author", ""));
                row.put("language", j.optString("lang", ""));
                row.put("download_url", j.optString("url", ""));
                list.add(row);
            }
        }
        Log.i(TAG, "loaded " + list.size() + " books from asset '" + ASSET + "'");
        CACHE = list;
        return CACHE;
    }
}
