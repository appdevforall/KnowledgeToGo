/*
 * ============================================================================
 * Name        : ShareManifestReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. Parses the sender gate's Share manifest (GET /v1/share/<id>/manifest) into
 *               the same ImportEntry list the disk import consumes. Share is the network twin of
 *               Export, so a manifest item is the read-side item JSON (model.ManifestItem.to_json)
 *               with two share annotations added by the gate: itemRef (the fetch handle) and delivered
 *               (an advisory sender hint). This maps itemRef onto the ImportEntry payload handle and
 *               strips the annotations from the entry JSON handed to /v1/import/item, so downstream
 *               ingest is identical to the disk path. Pure org.json: no HTTP, no Android, unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ShareUrls;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ShareManifestReader {

    private ShareManifestReader() {
    }

    /**
     * Parse the Share manifest bytes into import entries. Each entry's payload handle is the item's
     * {@code itemRef} (its index in the sender's scoped set, which the network carrier fetches by), and
     * its entry JSON is the item JSON with the {@code itemRef} / {@code delivered} annotations removed,
     * so it is the exact read-side item the import adapters already accept. Items the sender reported as
     * missing (unknown app / not found) are not in {@code items}, so they never produce an entry. An
     * item that cannot be addressed (no app, no localRef, or a non-numeric itemRef) is skipped.
     */
    public static List<ImportEntry> parse(byte[] manifestBytes) throws Exception {
        List<ImportEntry> out = new ArrayList<>();
        if (manifestBytes == null || manifestBytes.length == 0) {
            return out;
        }
        JSONObject root = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        JSONArray items = root.optJSONArray("items");
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String app = item.optString("app", "");
            JSONObject identity = item.optJSONObject("identity");
            String localRef = identity != null ? identity.optString("localRef", "") : "";
            String itemRef = item.optString("itemRef", "");
            if (app.isEmpty() || localRef.isEmpty() || !ShareUrls.isValidItemRef(itemRef)) {
                continue;   // not addressable or a drifted / hostile ref: skip, never fetch it
            }
            long bytes = item.optLong("bytes", 0L);
            String title = item.optString("title", localRef);
            // The entry handed to /v1/import/item is the pure read-side item (no share annotations),
            // so network ingest matches the disk path exactly. itemRef is already captured above, so
            // dropping it (and delivered) from this freshly parsed item is safe.
            item.remove("itemRef");
            item.remove("delivered");
            out.add(new ImportEntry(app, localRef, title, bytes, itemRef, item.toString()));
        }
        return out;
    }
}
