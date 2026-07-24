/*
 * ============================================================================
 * Name        : BooksProvisioner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. Post-install drain of the Books wishlist. Once the system is installed
 *               and the server is up (the live REST route is available), this hands the banked
 *               order to the existing BooksDownloadService — the same foreground, one-at-a-time,
 *               per-item-retry engine used by Get More — so provisioning is seamless and needs no
 *               new download machinery. Books is a LIVE operation (server up), so this must only be
 *               called when the server is alive. Idempotent: the wishlist is cleared once handed
 *               off (the service then owns retry), so it won't re-drain.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class BooksProvisioner {
    private BooksProvisioner() {}

    /** True when there is a banked Books order waiting to be provisioned. */
    public static boolean hasPending(Context ctx) {
        return BooksWishlist.size(ctx) > 0;
    }

    /** Hand the wishlist to BooksDownloadService (requires the server to be up) and clear it.
     *  No-op if empty or a session is already running. */
    public static void drain(Context ctx) {
        if (BooksDownloadService.isRunning() || BooksDownloadService.hasSession()) return;
        JSONArray order = BooksWishlist.all(ctx);
        List<String> ids = new ArrayList<>(), titles = new ArrayList<>(), urls = new ArrayList<>();
        for (int i = 0; i < order.length(); i++) {
            JSONObject o = order.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            ids.add(id);
            titles.add(o.optString("title", ""));
            urls.add(o.optString("url", ""));
        }
        if (ids.isEmpty()) { BooksWishlist.clear(ctx); return; }
        BooksDownloadService.start(ctx.getApplicationContext(),
                ids.toArray(new String[0]), titles.toArray(new String[0]), urls.toArray(new String[0]));
        BooksWishlist.clear(ctx);   // handed off; the service owns retry from here
    }
}
