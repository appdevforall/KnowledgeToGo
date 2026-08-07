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
        // ADFA-4900: proot (runrole) and REST downloads must not run at the same time — Ansible forks
        // background processes and concurrent REST work is a recipe for corruption. Defer REST while a
        // module-queue (proot) job is pending or running; a later drain pass picks it up once idle.
        if (org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()
                || MapsProvisioner.hasPending(ctx)) {
            Log.d(TAG, "books drain deferred: proot (runrole) work is pending/running");
            return;
        }
        if (BooksDownloadService.isRunning() || BooksDownloadService.hasSession()) {
            Log.d(TAG, "books drain skipped: a session is already active");
            return;
        }
        // ADFA-4954 (ADR-4954 D8): the live REST streams also serialize against each other.
        // Each measures free space independently and at a different moment, so all of them can
        // pass their own check and jointly fill the disk. A Kolibri channel runs to tens of GB.
        if (org.iiab.controller.kolibri.presentation.KolibriSeedRepository.get().hasSession()) {
            Log.d(TAG, "books drain deferred: a Kolibri seeding session is active");
            return;
        }
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
        BooksDownloadService.start(ctx.getApplicationContext(),
                ids.toArray(new String[0]), titles.toArray(new String[0]), urls.toArray(new String[0]));
        // TODO(ADFA-4874): clearing here means that if the process dies mid-download both the
        // wishlist and the service's in-memory state are lost (orphaned partial). The durable
        // background-jobs monitor should own this hand-off so it survives process death.
        BooksWishlist.clear(ctx);   // handed off; the service owns retry from here
    }
}
