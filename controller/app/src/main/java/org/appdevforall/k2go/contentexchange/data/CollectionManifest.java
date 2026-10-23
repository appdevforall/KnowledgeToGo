/*
 * ============================================================================
 * Name        : CollectionManifest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Assembles the top-level manifest.json of an export bundle from the
 *               per-item entries the plan returned, stamping each with the payload file name the
 *               carrier stored. The entry is used verbatim (identity, dedup, integrity) so the
 *               import side can pass items[] straight to the core; only payload is set here.
 *               Pure JSON assembly (no HTTP, no Android): unit-testable with org.json.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class CollectionManifest {

    /** The manifest format tag the import side keys on; bump when the shape changes. */
    public static final String FORMAT = "k2go-content-manifest/1";

    private CollectionManifest() {}

    /**
     * Build the manifest bytes for the given items, in order, stamping each entry's {@code payload}
     * with the name from {@code payloadNames}. An entry without a captured source entry is skipped.
     */
    public static byte[] build(Map<ItemRef, JSONObject> entries, List<ItemRef> order,
                               Map<ItemRef, String> payloadNames) throws JSONException {
        JSONArray items = new JSONArray();
        for (ItemRef ref : order) {
            JSONObject captured = entries.get(ref);
            if (captured == null) {
                continue;
            }
            // Deep-copy via text so the captured entry stays clean for a retry, then stamp payload.
            JSONObject entry = new JSONObject(captured.toString());
            entry.put("payload", payloadNames.get(ref));
            items.put(entry);
        }
        JSONObject manifest = new JSONObject();
        manifest.put("format", FORMAT);
        manifest.put("generated", nowIso8601());
        manifest.put("source", new JSONObject().put("env", "k2go"));
        manifest.put("items", items);
        return manifest.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    private static String nowIso8601() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }
}
