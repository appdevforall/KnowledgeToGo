/*
 * ============================================================================
 * Name        : CatalogManifestParser.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Parses the manifest JSON the weekly
 *               workflow publishes into a CatalogManifest. Never throws — a
 *               malformed manifest yields null, and the caller keeps the current
 *               catalog.
 * ============================================================================
 */
package org.iiab.controller.catalog.data;

import org.iiab.controller.catalog.domain.CatalogManifest;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class CatalogManifestParser {

    private CatalogManifestParser() {
    }

    /** @return the parsed manifest, or null if the input is missing or malformed. */
    public static CatalogManifest parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            List<CatalogManifest.Item> items = new ArrayList<>();
            JSONArray arr = o.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    String id = it.optString("id", "");
                    if (!id.isEmpty()) {
                        items.add(new CatalogManifest.Item(id, it.optInt("version", 0)));
                    }
                }
            }
            return new CatalogManifest(
                    o.optString("catalog", ""),
                    o.optString("version", ""),
                    o.optString("generated", ""),
                    o.optString("hash", ""),
                    o.optString("url", ""),
                    items);
        } catch (Throwable t) {
            return null;
        }
    }
}
