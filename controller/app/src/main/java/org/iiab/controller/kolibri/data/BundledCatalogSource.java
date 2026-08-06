/*
 * ============================================================================
 * Name        : BundledCatalogSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads the Kolibri channel catalog shipped in the APK.
 *               Blocking; call from an IO thread (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.kolibri.domain.Channel;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The channel catalog bundled with the app.
 *
 * <p>Format is JSON Lines, one object per line, for the reason ADR-4853 gave for
 * the Books catalog: channel names and descriptions carry commas, quotes and
 * accents in every script Kolibri publishes in, and a naive CSV split corrupts
 * rows. JSONL is delimiter-safe and streams line by line, so a malformed line
 * costs that line rather than the file.
 *
 * <p>The first line is a header object carrying {@code generated}; every
 * subsequent line is a channel. Parsed once and cached for the process, like
 * {@code KiwixCatalog}: the file does not change while the app runs.
 *
 * <p>Blocking. Never throws — a missing or unreadable asset yields an empty
 * catalog, because a picker with no rows is recoverable and a crash is not.
 */
public final class BundledCatalogSource {

    private static final String TAG = "K2Go-Kolibri";
    private static final String ASSET = "kolibri_catalog.jsonl";

    /** Marks the header line, so it is never mistaken for a channel. */
    private static final String HEADER_KEY = "catalog";

    private static volatile List<Channel> cachedChannels;
    private static volatile String cachedGeneratedOn = "";

    private final Context appContext;

    public BundledCatalogSource(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Every channel in the asset, in file order. Never null. */
    public List<Channel> channels() {
        load();
        return cachedChannels;
    }

    /** ISO-8601 date from the header line, or empty. */
    public String generatedOn() {
        load();
        return cachedGeneratedOn;
    }

    /** Drops the cache. For tests and for a future in-place catalog refresh. */
    public static void invalidate() {
        cachedChannels = null;
        cachedGeneratedOn = "";
    }

    private void load() {
        if (cachedChannels != null) {
            return;
        }
        synchronized (BundledCatalogSource.class) {
            if (cachedChannels != null) {
                return;
            }
            List<Channel> out = new ArrayList<>();
            String generated = "";
            int skipped = 0;

            try (InputStream is = appContext.getAssets().open(ASSET);
                 BufferedReader r = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    try {
                        JSONObject o = new JSONObject(t);
                        if (o.has(HEADER_KEY)) {
                            generated = o.optString("generated", "");
                            continue;
                        }
                        Channel c = StudioCatalogMapper.channel(o);
                        if (c == null) {
                            skipped++;
                        } else {
                            out.add(c);
                        }
                    } catch (Exception badLine) {
                        skipped++;
                    }
                }
            } catch (Exception e) {
                // A missing asset is a build problem, not a runtime one, but it
                // must not take the app down: log loudly and show nothing.
                Log.w(TAG, "bundled catalog unavailable: " + e.getMessage());
            }

            if (skipped > 0) {
                Log.w(TAG, "bundled catalog: skipped " + skipped + " unusable line(s)");
            }
            cachedGeneratedOn = generated;
            cachedChannels = Collections.unmodifiableList(out);
        }
    }
}
