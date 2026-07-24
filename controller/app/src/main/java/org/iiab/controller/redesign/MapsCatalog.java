/*
 * ============================================================================
 * Name        : MapsCatalog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848. Offline size catalog for the Maps "Choose" screen. Reads
 *               assets/maps_sizes.csv (group,level,bytes,date), which the refreshMapsSizes
 *               Gradle task regenerates from the maps mirror's .meta4 pointers at package
 *               time — so the last-known sizes are captured automatically, never hand-kept.
 *               Sizes are whole-world pmtiles and can be very large; the Choose screen's
 *               free-space guard is what keeps the estimate honest on a phone.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MapsCatalog {

    private static final String TAG = "MapsCatalog";
    private static final String CSV_ASSET = "maps_sizes.csv";
    private static final long MB = 1024L * 1024L;

    /** Parsed CSV (group|level -> bytes), loaded once per process. */
    private static volatile Map<String, Long> csvSizes;

    public MapsCatalog(Context context) { ensureCsvLoaded(context); }

    private static void ensureCsvLoaded(Context context) {
        if (csvSizes != null || context == null) return;
        synchronized (MapsCatalog.class) {
            if (csvSizes != null) return;
            Map<String, Long> m = new HashMap<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(CSV_ASSET)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split(",");
                    if (p.length >= 3) {
                        try {
                            m.put(key(p[0].trim(), p[1].trim()), Long.parseLong(p[2].trim()));
                        } catch (NumberFormatException ignore) { /* skip malformed row */ }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "maps_sizes.csv not read (" + e.getMessage() + "); Choose uses its built-in fallbacks");
            }
            csvSizes = m;
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
}
