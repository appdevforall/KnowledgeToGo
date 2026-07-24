/*
 * ============================================================================
 * Name        : KiwixCatalog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. Kiwix ZIM catalog for the "Wikipedia & ZIM content" screens.
 *
 *               Reads a COMPLETE OFFLINE catalog baked into assets/kiwix_catalog.csv
 *               (category,creator,lang,flavour,bytes,date,file), so the menu draws instantly
 *               with no on-device scraping. The CSV is generated off-device from the Kiwix
 *               directory listings (download.kiwix.org, with a mirror fallback) — the servers
 *               throttle bots, so we do NOT scrape continuously from the app. A lightweight
 *               background refresh (re-downloading an updated CSV) can be layered on later.
 *
 *               Shape built in memory:
 *                 { project: { lang: { "<creator><flavour>": {creator,flavour,size,date,file} } } }
 *               Files with no language token are bucketed under "mul" (language-agnostic).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public final class KiwixCatalog {
    private KiwixCatalog() {}

    private static final String TAG = "KiwixCatalog";
    private static final String CSV_ASSET = "kiwix_catalog.csv";

    /** Language-agnostic bucket (files whose name carries no language token, e.g. many videos). */
    public static final String MUL = "mul";

    public interface Listener {
        void onReady(JSONObject catalog);
        void onError(String message);
    }

    private static volatile JSONObject inMemory;

    /** Loads the baked CSV (once per process) off the main thread; posts back on the main thread. */
    public static void getOrFetch(Context context, Listener listener) {
        JSONObject mem = inMemory;
        if (mem != null) { post(() -> listener.onReady(mem)); return; }

        new Thread(() -> {
            JSONObject db = loadCsv(context);
            if (db != null && db.length() > 0) {
                inMemory = db;
                post(() -> listener.onReady(db));
            } else {
                post(() -> listener.onError("Catalog unavailable"));
            }
        }).start();
    }

    private static JSONObject loadCsv(Context context) {
        JSONObject db = new JSONObject();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(context.getAssets().open(CSV_ASSET)))) {
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isEmpty()) continue;
                String[] p = line.split(",", 7);
                if (p.length < 7) continue;
                String category = p[0].trim();
                String creator = p[1].trim();
                String lang = p[2].trim().isEmpty() ? MUL : p[2].trim();
                String flavour = p[3].trim();
                long bytes;
                try { bytes = Long.parseLong(p[4].trim()); } catch (NumberFormatException e) { bytes = 0; }
                String date = p[5].trim();
                String file = p[6].trim();

                JSONObject proj = db.optJSONObject(category);
                if (proj == null) { proj = new JSONObject(); db.put(category, proj); }
                JSONObject langObj = proj.optJSONObject(lang);
                if (langObj == null) { langObj = new JSONObject(); proj.put(lang, langObj); }

                JSONObject v = new JSONObject();
                v.put("creator", creator);
                v.put("flavour", flavour);
                v.put("size", bytes);
                v.put("date", date);
                v.put("file", file);
                langObj.put(creator + "" + flavour, v);
            }
        } catch (Exception e) {
            Log.w(TAG, "kiwix_catalog.csv not read: " + e.getMessage());
            return null;
        }
        return db;
    }

    // ---- query helpers ---------------------------------------------------------

    /** Entries (creatorKey -> {...}) for a project+language, or null. */
    public static JSONObject langData(JSONObject catalog, String project, String lang) {
        if (catalog == null) return null;
        JSONObject p = catalog.optJSONObject(project);
        return p == null ? null : p.optJSONObject(lang);
    }

    /** Files available for a project in a language (strict; "mul" is its own language). 0 => disabled. */
    public static int count(JSONObject catalog, String project, String lang) {
        JSONObject ld = langData(catalog, project, lang);
        return ld == null ? 0 : ld.length();
    }

    /** Total files across all languages for a project (the big "2,465"-style number). */
    public static int totalFiles(JSONObject catalog, String project) {
        if (catalog == null) return 0;
        JSONObject p = catalog.optJSONObject(project);
        if (p == null) return 0;
        int n = 0;
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            JSONObject ld = p.optJSONObject(it.next());
            if (ld != null) n += ld.length();
        }
        return n;
    }

    /** Languages a project offers, including the "mul" (multilingual) bucket as its own entry. */
    public static Set<String> languages(JSONObject catalog, String project) {
        Set<String> out = new LinkedHashSet<>();
        if (catalog == null) return out;
        JSONObject p = catalog.optJSONObject(project);
        if (p == null) return out;
        for (Iterator<String> it = p.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static void post(Runnable r) { new Handler(Looper.getMainLooper()).post(r); }
}
