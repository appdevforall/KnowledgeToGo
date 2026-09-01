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
 *               called when the server is alive. ADFA-4897: the wishlist is NOT cleared at hand-off;
 *               the service drops each entry only once its book is confirmed added, so a process death
 *               re-drains only what did not finish. Re-entrant: a running session short-circuits drain().
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class BooksProvisioner {
    private BooksProvisioner() {}

    private static final String TAG = "K2Go-Provision";

    /** True when there is a banked Books order waiting to be provisioned. */
    public static boolean hasPending(Context ctx) {
        return BooksWishlist.size(ctx) > 0;
    }

    /** Hand the wishlist to BooksDownloadService (requires the server to be up) and clear it.
     *  No-op if empty or a session is already running. */
    public static void drain(Context ctx) {
        // The deferral rule (proot in flight, another stream unfinished) is one source, shared with
        // ZIM and Kolibri — see ContentAdmission for why each check is there.
        if (!org.iiab.controller.system.data.ContentAdmission.canStart(ctx, org.iiab.controller.system.domain.ContentType.BOOKS)) return;
        JSONArray order = BooksWishlist.all(ctx);
        Log.i(TAG, "books drain: " + order.length() + " in wishlist");
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
        Log.i(TAG, "books drain: handing " + ids.size() + " to BooksDownloadService");
        // ADFA-4897: do NOT clear the wishlist here. The service drops each book from the wishlist only
        // when it is confirmed added (BooksDownloadService.onItemDone), and clears the rest on an
        // explicit Cancel — so a process death leaves the not-done books banked for the next drain.
        BooksDownloadService.start(ctx.getApplicationContext(),
                ids.toArray(new String[0]), titles.toArray(new String[0]), urls.toArray(new String[0]));
    }
}
