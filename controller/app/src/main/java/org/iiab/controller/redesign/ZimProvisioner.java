/*
 * ============================================================================
 * Name        : ZimProvisioner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. Post-install drain of the ZIM/Wikipedia wishlist. Mirrors
 *               BooksProvisioner: once the system is installed and the server is up, it resolves
 *               each banked selector "project|lang|flavour" against the offline catalog
 *               (kiwix_catalog.csv, via KiwixCatalog) into a real ZIM file + label + size, then
 *               hands them to the existing ZimDownloadService (the live REST route, foreground,
 *               per-item retry). ZIM is a LIVE operation (server up), so this must only run when
 *               the server is alive. ADFA-4897: the wishlist is NOT cleared at hand-off; the service
 *               drops each entry only once its item is confirmed done, so a process death re-drains
 *               only what did not finish. Re-entrant: a running session short-circuits drain().
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ZimProvisioner {
    private ZimProvisioner() {}

    private static final String TAG = "K2Go-Provision";

    /** True when there is a banked ZIM order waiting to be provisioned. */
    public static boolean hasPending(Context ctx) {
        return ZimWishlist.size(ctx) > 0;
    }

    /** Resolve the wishlist against the offline catalog and hand it to ZimDownloadService.
     *  No-op if empty or a session is already running. Requires the server to be up. */
    public static void drain(Context ctx) {
        // The deferral rule (proot in flight, another stream unfinished) is one source, shared with
        // Books and Kolibri — see ContentAdmission for why each check is there.
        if (!org.iiab.controller.system.data.ContentAdmission.canStart(ctx, org.iiab.controller.system.domain.ContentType.ZIM)) return;
        if (ZimWishlist.size(ctx) == 0) return;
        final Context app = ctx.getApplicationContext();
        Log.i(TAG, "zim drain: " + ZimWishlist.size(app) + " in wishlist; loading catalog");
        KiwixCatalog.getOrFetch(app, new KiwixCatalog.Listener() {
            @Override public void onReady(JSONObject catalog) { resolveAndStart(app, catalog); }
            @Override public void onError(String message) { Log.w(TAG, "zim drain: catalog load failed: " + message); }
        });
    }

    private static void resolveAndStart(Context app, JSONObject catalog) {
        JSONArray order = ZimWishlist.all(app);
        List<String> keys = new ArrayList<>(), files = new ArrayList<>(), labels = new ArrayList<>();
        List<Long> bytes = new ArrayList<>();
        for (int i = 0; i < order.length(); i++) {
            JSONObject o = order.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key", "");
            ZimSelection.Item it = ZimSelection.resolve(catalog, key);
            if (it == null) continue;
            keys.add(key);      // wishlist key ("project|lang|flavour") — dropped per-item once done
            files.add(it.id);   // "<project>/<file>" (ADFA-5042)
            labels.add(ZimItemLabel.of(it.project,
                    it.entry.optString("creator"), it.entry.optString("flavour")));
            bytes.add(it.entry.optLong("size", o.optLong("bytes", 0)));
        }
        if (files.isEmpty()) { Log.w(TAG, "zim drain: nothing resolved from wishlist"); ZimWishlist.clear(app); return; }
        long[] b = new long[bytes.size()];
        for (int i = 0; i < b.length; i++) b[i] = bytes.get(i);
        Log.i(TAG, "zim drain: handing " + files.size() + " to ZimDownloadService");
        // ADFA-4897: do NOT clear the wishlist here. The service now drops each item from the wishlist
        // only when it is confirmed DONE (ZimDownloadService.onItemDone), and clears the rest on an
        // explicit Cancel. So a process death mid-download leaves exactly the not-done items banked,
        // and the next drain re-hands them off — no orphaned partial, no re-download of finished items.
        ZimDownloadService.start(app, keys.toArray(new String[0]), files.toArray(new String[0]),
                labels.toArray(new String[0]), b);
    }

    // ADFA-5074: a private label() lived here and a different one lived in the Get More path, so
    // the same ZIM was named one way when it came from the wizard and another from Get More —
    // this one never combined creator and flavour, the other did. Both are now ZimItemLabel,
    // which is pure and has the rules under test. Nothing else about this class changed: it was
    // already the single resolution for a banked order, and Get More now goes through it too
    // instead of keeping a third copy.
}
