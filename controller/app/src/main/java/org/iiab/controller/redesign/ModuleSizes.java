/*
 * ============================================================================
 * Name        : ModuleSizes.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4958. Per-module install size (bytes), read from assets/module_sizes.csv
 *               which the refreshModuleSizes Gradle task regenerates from the full build log's
 *               per-role disk_usage accounting. Unknown key (e.g. matomo, maps) -> -1 (show NA).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public final class ModuleSizes {

    private ModuleSizes() {}

    private static final String CSV = "module_sizes.csv";
    private static volatile Map<String, Long> sizes;

    /** @return install size in bytes for a module key, or -1 if unknown (show "NA"). */
    public static long bytesFor(Context ctx, String key) {
        load(ctx);
        Long v = (key == null) ? null : sizes.get(key);
        return v == null ? -1L : v;
    }

    private static void load(Context ctx) {
        if (sizes != null) return;
        synchronized (ModuleSizes.class) {
            if (sizes != null) return;
            Map<String, Long> m = new HashMap<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(ctx.getAssets().open(CSV)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split(",");
                    if (p.length >= 2) {
                        try { m.put(p[0].trim(), Long.parseLong(p[1].trim())); }
                        catch (NumberFormatException ignore) { /* skip malformed row */ }
                    }
                }
            } catch (Exception ignore) { /* missing CSV -> everything unknown (NA) */ }
            sizes = m;
        }
    }
}
