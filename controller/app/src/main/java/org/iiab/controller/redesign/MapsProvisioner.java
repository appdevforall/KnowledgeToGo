/*
 * ============================================================================
 * Name        : MapsProvisioner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4900. Post-install drain of the maps wishlist. Mirrors Books/ZimProvisioner:
 *               once the system is installed, it hands the banked per-layer selection to the
 *               module-queue engine (InstallService ACTION_START_MODULES {"maps"} + the selection),
 *               which writes the maps_* local_vars and runs runrole maps with the shared verdict /
 *               revert / progress. Unlike ZIM this does not need the server up, only the rootfs, so
 *               it can run at the same post-install drain point. Idempotent: cleared once handed off.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.iiab.controller.install.presentation.InstallService;
import org.iiab.controller.install.presentation.ModuleQueueRepository;

public final class MapsProvisioner {
    private MapsProvisioner() {}

    private static final String TAG = "K2Go-Provision";

    /** True when there is a banked maps selection waiting to be applied. */
    public static boolean hasPending(Context ctx) {
        return MapsWishlist.has(ctx);
    }

    /** Hand the banked maps selection to the module-queue engine. No-op if empty or a queue is
     *  already running. Requires the rootfs (runs runrole maps in the proot). */
    public static void drain(Context ctx) {
        if (!MapsWishlist.has(ctx)) return;
        if (ModuleQueueRepository.get().isRunning()) return;
        final Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, InstallService.class);
        i.setAction(InstallService.ACTION_START_MODULES);
        i.putExtra(InstallService.EXTRA_MODULES, new String[]{"maps"});
        i.putExtra(InstallService.EXTRA_MAPS_VECTOR, MapsWishlist.base(app));
        i.putExtra(InstallService.EXTRA_MAPS_SAT, MapsWishlist.sat(app));
        i.putExtra(InstallService.EXTRA_MAPS_TERRAIN, MapsWishlist.terrain(app));
        i.putExtra(InstallService.EXTRA_MAPS_SEARCH, MapsWishlist.search(app));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
        else app.startService(i);
        Log.i(TAG, "maps drain: handed the banked selection to InstallService (runrole maps)");
        // Handed off; the module-queue owns the run from here.
        MapsWishlist.clear(app);
    }
}
