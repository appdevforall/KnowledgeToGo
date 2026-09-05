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
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import org.appdevforall.k2go.catalog.data.CatalogOverlay;
import org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler;
import org.appdevforall.k2go.config.DownloadEndpoints;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public final class KiwixCatalog {
    private KiwixCatalog() {}

    private static final String TAG = "KiwixCatalog";
    private static final String CSV_ASSET = "kiwix_catalog.csv";

    // K2GO-390 (ADR-390): the catalog is refreshed like Kolibri's -- a hosted manifest + overlay,
    // ETag/hash-gated -- reusing the catalog-agnostic core. Flat, so no tree machinery. The overlay
    // (when a newer CSV has been pulled) is preferred over the APK asset; the asset is the offline
    // baseline. CATALOG name namespaces its refresh state; BASENAME is shared with the overlay so the
    // worker writes exactly where loadCsv reads.
    private static final String CATALOG_NAME = "kiwix";
    private static final String MANIFEST_URL = DownloadEndpoints.APK_REPO + "/catalogs/kiwix.manifest.json";

    /** Language-agnostic bucket (files whose name carries no language token, e.g. many videos). */
    public static final String MUL = "mul";

    public interface Listener {
        void onReady(JSONObject catalog);
        void onError(String message);
    }

    private static volatile JSONObject inMemory;
    // Which source the cache came from: -1 = not loaded, 0 = APK asset, >0 = the overlay's lastModified.
    private static volatile long cachedOverlayMtime = -1L;

    /**
     * Loads the catalog (overlay if pulled, else the baked asset) off the main thread; posts back on the
     * main thread. K2GO-390: also nudges the freshness refresh (weekly + an opportunistic TTL-gated
     * check now, since the picker is opening). Both refreshes are network-constrained WorkManager jobs,
     * so offline is a silent no-op -- the asset/overlay stays the offline baseline. See ADR-390.
     */
    public static void getOrFetch(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        nudgeRefresh(app);

        JSONObject mem;
        synchronized (KiwixCatalog.class) {
            reloadIfOverlayChanged(app);   // drop the cache if a newer overlay landed
            mem = inMemory;
        }
        if (mem != null) { post(() -> listener.onReady(mem)); return; }

        new Thread(() -> {
            JSONObject db;
            synchronized (KiwixCatalog.class) {   // one loader wins; the rest reuse the cache
                if (inMemory == null) inMemory = loadCsv(app);
                db = inMemory;
            }
            if (db != null && db.length() > 0) post(() -> listener.onReady(db));
            else post(() -> listener.onError("Catalog unavailable"));
        }).start();
    }

    // Nudge the freshness refresh once per process (K2GO-390): weekly (KEEP) + an opportunistic,
    // TTL-gated check. Network-constrained, so offline is a no-op. A 404 forces its own check
    // (forceRefresh), so this need not run on every catalog open (the drain opens it every ~2 s).
    private static volatile boolean refreshNudged = false;

    private static void nudgeRefresh(Context app) {
        if (refreshNudged) return;
        refreshNudged = true;
        CatalogRefreshScheduler.scheduleWeekly(app, CATALOG_NAME, MANIFEST_URL, CSV_ASSET);
        CatalogRefreshScheduler.refreshNow(app, CATALOG_NAME, MANIFEST_URL, CSV_ASSET);
    }

    /**
     * K2GO-390: force a freshness check that bypasses the TTL gate. Called when a download 404s -- the
     * catalog may have rolled to a newer dated file within the TTL window. Network-constrained, so
     * offline is a silent no-op. Once the overlay lands, the next {@link #getOrFetch} adopts it and the
     * drain re-resolves the (date-free) key to the current file. See ADR-390.
     */
    public static void forceRefresh(Context context) {
        CatalogRefreshScheduler.forceRefresh(context.getApplicationContext(), CATALOG_NAME, MANIFEST_URL, CSV_ASSET);
    }

    /**
     * K2GO-390: the current catalog version tag -- the overlay's mtime, or 0 for the baked asset. The
     * self-heal counts failures against this ({@link ZimWishlist#bumpAttempts}): a refresh that replaces
     * the overlay moves the tag and renews the retry budget; an unchanging catalog keeps it stable so the
     * budget can reach its cap and drop a genuinely-gone item. Kept here so "which catalog version" has a
     * single owner (the overlay basename lives only in this class). See ADR-390.
     */
    public static long catalogVersionTag(Context context) {
        File overlay = CatalogOverlay.file(context.getApplicationContext(), CSV_ASSET);
        return overlay.exists() ? overlay.lastModified() : 0L;
    }

    /** Drop the cache so the next load re-reads. K2GO-390: called after a refresh pulls a new overlay. */
    public static void invalidate() {
        inMemory = null;
        cachedOverlayMtime = -1L;
    }

    /** If the overlay's mtime differs from what the cache was loaded from, drop the cache (ADR-390). */
    private static void reloadIfOverlayChanged(Context ctx) {
        if (inMemory == null) return;
        File overlay = CatalogOverlay.file(ctx, CSV_ASSET);
        long mtime = overlay.exists() ? overlay.lastModified() : 0L;
        if (mtime != cachedOverlayMtime) invalidate();
    }

    private static JSONObject loadCsv(Context context) {
        JSONObject db = new JSONObject();
        // K2GO-390: prefer the pulled overlay over the APK asset; the asset is the offline baseline.
        File overlay = CatalogOverlay.file(context, CSV_ASSET);
        boolean useOverlay = overlay.exists();
        long mtime = useOverlay ? overlay.lastModified() : 0L;
        try (InputStream in = useOverlay ? new FileInputStream(overlay) : context.getAssets().open(CSV_ASSET);
                BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
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
        cachedOverlayMtime = mtime;   // remember which source (asset=0 / overlay mtime) fed the cache
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
