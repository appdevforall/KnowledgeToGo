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
 *               the server is alive. Idempotent: the wishlist is cleared once handed off.
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
        // ADFA-4900: proot (runrole) and REST downloads must not run concurrently (Ansible forks
        // background processes; concurrent REST work risks corruption). Defer ZIM while a module-queue
        // (proot) job is pending or running; a later drain pass runs it once proot is idle.
        if (org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()
                || MapsProvisioner.hasPending(ctx)) {
            Log.d(TAG, "zim drain deferred: proot (runrole) work is pending/running");
            return;
        }
        if (ZimDownloadService.isRunning() || ZimDownloadService.hasSession()) return;
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
        List<String> files = new ArrayList<>(), labels = new ArrayList<>();
        List<Long> bytes = new ArrayList<>();
        for (int i = 0; i < order.length(); i++) {
            JSONObject o = order.optJSONObject(i);
            if (o == null) continue;
            String[] p = o.optString("key", "").split("\\|", 3);   // project | lang | entryKey
            if (p.length < 3) continue;
            JSONObject ld = KiwixCatalog.langData(catalog, p[0], p[1]);
            JSONObject v = ld != null ? ld.optJSONObject(p[2]) : null;
            if (v == null) continue;
            files.add(v.optString("file"));
            labels.add(label(p[0], v.optString("creator"), v.optString("flavour")));
            bytes.add(v.optLong("size", o.optLong("bytes", 0)));
        }
        if (files.isEmpty()) { Log.w(TAG, "zim drain: nothing resolved from wishlist"); ZimWishlist.clear(app); return; }
        long[] b = new long[bytes.size()];
        for (int i = 0; i < b.length; i++) b[i] = bytes.get(i);
        Log.i(TAG, "zim drain: handing " + files.size() + " to ZimDownloadService");
        ZimDownloadService.start(app, files.toArray(new String[0]), labels.toArray(new String[0]), b);
        // TODO(ADFA-4874): clearing here means that if the process dies mid-download both the
        // wishlist and the service's in-memory state are lost (orphaned partial). The durable
        // background-jobs monitor should own this hand-off so it survives process death.
        ZimWishlist.clear(app);   // handed off; the service owns retry from here
    }

    private static String label(String project, String creator, String flavour) {
        KiwixCategories.Category c = KiwixCategories.byKey(project);
        String cat = c != null ? c.title : project;
        if (flavour == null || flavour.isEmpty() || "all".equalsIgnoreCase(flavour)) {
            return (creator == null || creator.isEmpty()) ? cat : cat + " · " + creator;
        }
        return cat + " · " + flavour.replace('_', ' ').replace('-', ' ');
    }
}
