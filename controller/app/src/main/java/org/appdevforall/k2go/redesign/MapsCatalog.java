/*
 * ============================================================================
 * Name        : MapsCatalog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 / K2GO-394. Offline maps catalog for the "Choose" screen and
 *               the base-map download. Reads assets/maps_catalog.csv
 *               (group,level,file,bytes,date), which tools/build_maps_catalog.py regenerates
 *               from the maps mirror's .meta4 pointers at package time -- so the last-known
 *               file names and sizes are captured automatically, never hand-kept. The Choose
 *               screen uses the size (whole-world pmtiles are large; its free-space guard keeps
 *               the estimate honest); the download uses the file name as the id it sends to the
 *               box, which composes the mirror URL (K2GO-394, same split as kiwix).
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MapsCatalog {

    private static final String TAG = "MapsCatalog";
    private static final String CSV_ASSET = "maps_catalog.csv";
    private static final long MB = 1024L * 1024L;

    /** Parsed CSV (group|level -> bytes), loaded once per process. */
    private static volatile Map<String, Long> csvSizes;
    /** Parsed CSV (group|level -> mirror file name), loaded with the sizes. */
    private static volatile Map<String, String> csvFiles;

    public MapsCatalog(Context context) { ensureCsvLoaded(context); }

    private static void ensureCsvLoaded(Context context) {
        if (csvSizes != null || context == null) return;
        synchronized (MapsCatalog.class) {
            if (csvSizes != null) return;
            Map<String, Long> sizes = new HashMap<>();
            Map<String, String> files = new HashMap<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(CSV_ASSET)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // group,level,file,bytes,date
                    String[] p = line.split(",");
                    if (p.length >= 4) {
                        String k = key(p[0].trim(), p[1].trim());
                        String file = p[2].trim();
                        if (!file.isEmpty()) files.put(k, file);
                        try {
                            sizes.put(k, Long.parseLong(p[3].trim()));
                        } catch (NumberFormatException ignore) { /* skip malformed size */ }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "maps_catalog.csv not read (" + e.getMessage() + "); Choose uses its built-in fallbacks");
            }
            csvFiles = files;
            csvSizes = sizes;   // set last: it is the "loaded" flag the double-check reads
        }
    }

    private static String key(String group, String level) {
        return group.toLowerCase(Locale.US) + "|" + level.toLowerCase(Locale.US);
    }

    /**
     * Last-known size in whole MB for a group+level, or {@code fallbackMb} when the CSV is
     * missing/unreadable or has no row for this key. {@code level == null} (e.g. "Off") is 0.
     */
    public long sizeMb(String group, String level, long fallbackMb) {
        if (level == null) return 0;
        Map<String, Long> csv = csvSizes;
        if (csv != null) {
            Long bytes = csv.get(key(group, level));
            if (bytes != null && bytes > 0) return Math.max(1, Math.round(bytes / (double) MB));
        }
        return fallbackMb;
    }

    /**
     * K2GO-394: the mirror file name for a group+level, or {@code null} when the level is off
     * ({@code null}) or the catalog has no row for it. This is the download id the app sends to the
     * box; the box composes the real URL (MAPS_BASE_URL + file), so the app never holds the host.
     */
    public String fileFor(String group, String level) {
        if (group == null || level == null) return null;
        Map<String, String> files = csvFiles;
        if (files == null) return null;
        String f = files.get(key(group, level));
        return (f != null && !f.isEmpty()) ? f : null;
    }
}
