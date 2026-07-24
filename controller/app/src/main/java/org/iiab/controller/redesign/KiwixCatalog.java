/*
 * ============================================================================
 * Name        : KiwixCatalog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. Kiwix ZIM catalog for the "Wikipedia & ZIM content" screen.
 *               Generalizes the old Wikipedia-only fetch to EVERY project directory under the
 *               mirror's /zim/ (one level up), parsing <project>_<lang>_<flavour>_<YYYY-MM>.zim
 *               + size, keeping the newest date per (project, lang, flavour). Result shape:
 *                 { project: { lang: { flavour: {size:<bytes>, date:"YYYY-MM", file:"..."} } } }
 *               Cache-first (disk), background fetch, stale cache kept on failure.
 *
 *               Source is a MIRROR, not download.kiwix.org, which now blocks robots. The mirror
 *               base is a single constant so it can be repointed.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KiwixCatalog {
    private KiwixCatalog() {}

    private static final String TAG = "KiwixCatalog";

    /** download.kiwix.org blocks robots; use a complete mirror (has all 23 project dirs). */
    public static final String MIRROR_BASE = "https://mirrors.dotsrc.org/kiwix/zim/";

    private static final String CACHE_FILE = "kiwix_zim_catalog.json";
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000; // refetch at most daily

    public interface Listener {
        void onReady(JSONObject catalog);
        void onError(String message);
    }

    /** Filename (in the href) -> project_lang_flavour_date; group 2 is the date. Built per project. */
    private static Pattern filePattern(String project) {
        return Pattern.compile("href=\"[^\"]*?(" + Pattern.quote(project)
                + "_[A-Za-z0-9_.\\-]+?_(\\d{4}-\\d{2}))\\.zim\"");
    }

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(TiB|GiB|MiB|KiB|T|G|M|K|B)\\b");

    private static volatile JSONObject inMemory;

    /** Cache-first, then a background fetch of every project. Posts back on the main thread. */
    public static void getOrFetch(Context context, Listener listener) {
        JSONObject mem = inMemory;
        if (mem != null) { post(() -> listener.onReady(mem)); return; }

        new Thread(() -> {
            File cache = new File(context.getCacheDir(), CACHE_FILE);
            boolean fresh = cache.exists()
                    && (System.currentTimeMillis() - cache.lastModified()) < CACHE_TTL_MS;
            if (fresh) {
                JSONObject db = readCache(cache);
                if (db != null) { inMemory = db; post(() -> listener.onReady(db)); return; }
            }

            JSONObject db = new JSONObject();
            int ok = 0;
            for (KiwixCategories.Category c : KiwixCategories.ALL) {
                try {
                    String html = httpGet(MIRROR_BASE + c.key + "/");
                    parseProject(html, c.key, db);
                    ok++;
                } catch (Exception e) {
                    Log.w(TAG, "catalog fetch failed for " + c.key + ": " + e.getMessage());
                }
            }

            if (ok > 0) {
                writeCache(cache, db);
                inMemory = db;
                post(() -> listener.onReady(db));
                return;
            }
            // Everything failed: fall back to whatever cache we have, else report.
            JSONObject stale = readCache(cache);
            if (stale != null) { inMemory = stale; post(() -> listener.onReady(stale)); }
            else post(() -> listener.onError("Could not reach the content mirror"));
        }).start();
    }

    // ---- parsing ---------------------------------------------------------------

    private static void parseProject(String html, String project, JSONObject db) throws Exception {
        // Collect sizes with their positions (in document order); a listing row is
        // "<a href=file.zim>…</a>  <size>  <date>", so a file's size is the first size token
        // appearing after the file's href.
        List<Integer> sizePos = new ArrayList<>();
        List<Long> sizeVal = new ArrayList<>();
        Matcher sm = SIZE_PATTERN.matcher(html);
        while (sm.find()) {
            sizePos.add(sm.start());
            sizeVal.add(toBytes(sm.group(1), sm.group(2)));
        }

        JSONObject proj = db.optJSONObject(project);
        if (proj == null) { proj = new JSONObject(); db.put(project, proj); }

        Matcher fm = filePattern(project).matcher(html);
        int si = 0;
        while (fm.find()) {
            String stem = fm.group(1);   // project_lang_flavour_date (no .zim)
            String date = fm.group(2);   // YYYY-MM
            int fpos = fm.start();

            while (si < sizePos.size() && sizePos.get(si) <= fpos) si++;
            long bytes = si < sizeVal.size() ? sizeVal.get(si) : 0L;

            // Strip "project_" prefix and "_date" suffix -> "lang_flavour" (flavour may be empty).
            String mid = stem.substring(project.length() + 1, stem.length() - (date.length() + 1));
            String lang, flavour;
            int u = mid.indexOf('_');
            if (u < 0) { lang = mid; flavour = "all"; }
            else { lang = mid.substring(0, u); flavour = mid.substring(u + 1); }
            if (lang.isEmpty()) continue;

            JSONObject langObj = proj.optJSONObject(lang);
            if (langObj == null) { langObj = new JSONObject(); proj.put(lang, langObj); }

            JSONObject existing = langObj.optJSONObject(flavour);
            if (existing == null || date.compareTo(existing.optString("date", "")) > 0) {
                JSONObject v = new JSONObject();
                v.put("size", bytes);
                v.put("date", date);
                v.put("file", stem + ".zim");
                langObj.put(flavour, v);
            }
        }
    }

    private static long toBytes(String num, String unit) {
        double n;
        try { n = Double.parseDouble(num); } catch (NumberFormatException e) { return 0L; }
        double mult;
        switch (unit) {
            case "TiB": case "T": mult = 1024d * 1024 * 1024 * 1024; break;
            case "GiB": case "G": mult = 1024d * 1024 * 1024; break;
            case "MiB": case "M": mult = 1024d * 1024; break;
            case "KiB": case "K": mult = 1024d; break;
            default: mult = 1d; break; // "B"
        }
        return Math.round(n * mult);
    }

    // ---- query helpers (used by the landing + category screens) ----------------

    /** flavour -> {size,date,file} for a project+language, or null. */
    public static JSONObject langData(JSONObject catalog, String project, String lang) {
        if (catalog == null) return null;
        JSONObject p = catalog.optJSONObject(project);
        return p == null ? null : p.optJSONObject(lang);
    }

    /** Number of flavours available for a project in a language (0 => disabled in the UI). */
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

    /** Languages a project offers (for the language selector / availability). */
    public static Set<String> languages(JSONObject catalog, String project) {
        Set<String> out = new LinkedHashSet<>();
        if (catalog == null) return out;
        JSONObject p = catalog.optJSONObject(project);
        if (p == null) return out;
        for (Iterator<String> it = p.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    // ---- io --------------------------------------------------------------------

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (K2Go)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject readCache(File cache) {
        if (!cache.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(cache)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "cache read error: " + e.getMessage());
            return null;
        }
    }

    private static void writeCache(File cache, JSONObject db) {
        try (FileOutputStream fos = new FileOutputStream(cache)) {
            fos.write(db.toString().getBytes());
        } catch (Exception e) {
            Log.w(TAG, "cache write error: " + e.getMessage());
        }
    }

    private static void post(Runnable r) { new Handler(Looper.getMainLooper()).post(r); }
}
