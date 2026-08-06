/*
 * ============================================================================
 * Name        : ZimSelection.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5042. Single source for resolving a banked ZIM selection ("project|lang|entryKey")
 *               against the offline catalog into its download id + catalog entry. The download id is
 *               "<project>/<file>" — the contract the server (dash-node, sockets/kiwix.exec.ts) relies on
 *               to build the correct mirror URL (/zim/<project>/<file>). Kept in one place so the two
 *               builders (ZimPreparingFragment from the cart, ZimProvisioner from the wishlist) can't
 *               drift on the id shape. Label/size formatting stays with each caller (they differ).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import org.json.JSONObject;

final class ZimSelection {
    private ZimSelection() {}

    /** A resolved selection: its download id and the catalog entry behind it. */
    static final class Item {
        final String id;         // "<project>/<file>" — the download id sent to the server (ADFA-5042)
        final String project;    // KiwixCategories key = mirror project directory
        final JSONObject entry;  // catalog entry (creator, flavour, file, size, …)
        Item(String id, String project, JSONObject entry) {
            this.id = id; this.project = project; this.entry = entry;
        }
    }

    /** Resolve a "project|lang|entryKey" selection key against the catalog, or null if it doesn't
     *  resolve. The download id is "&lt;project&gt;/&lt;file&gt;" — the single source of the server URL
     *  contract; do not build it anywhere else. */
    static Item resolve(JSONObject catalog, String selectionKey) {
        if (catalog == null || selectionKey == null) return null;
        String[] p = selectionKey.split("\\|", 3);   // project | lang | entryKey
        if (p.length < 3) return null;
        JSONObject ld = KiwixCatalog.langData(catalog, p[0], p[1]);
        JSONObject v = ld != null ? ld.optJSONObject(p[2]) : null;
        if (v == null) return null;
        return new Item(p[0] + "/" + v.optString("file"), p[0], v);
    }
}
