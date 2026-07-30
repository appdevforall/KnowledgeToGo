/*
 * ============================================================================
 * Name        : ModuleProvisioner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. Drain of the module wishlist to the module-queue engine. Mirrors
 *               MapsProvisioner: hands the scheduled module keys to InstallService
 *               (ACTION_START_MODULES), which runs one runrole per module in the proot with the
 *               shared verdict / revert-on-fail / progress (ModuleQueueRepository). Runs on the LIVE
 *               system, so the queue stops the server before the runroles and the app restarts it
 *               after (see ADFA-4919). Idempotent: no-op if empty or a queue is already running;
 *               cleared once handed off. Plain modules use the generic <key>_install/_enabled path
 *               (no per-layer config); maps, if ever scheduled here, still carries its own extras via
 *               its own provisioner — this one is for the description-only proot modules.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.iiab.controller.install.presentation.InstallService;
import org.iiab.controller.install.presentation.ModuleQueueRepository;

public final class ModuleProvisioner {
    private ModuleProvisioner() {}

    private static final String TAG = "K2Go-Provision";

    /** True when there are modules scheduled to install. */
    public static boolean hasPending(Context ctx) {
        return ModuleWishlist.has(ctx);
    }

    /** Hand the scheduled modules to the module-queue engine. No-op if empty or a queue is already
     *  running. Requires the rootfs; the queue owns server stop/restart + progress from here. */
    public static void drain(Context ctx) {
        if (!ModuleWishlist.has(ctx)) return;
        if (ModuleQueueRepository.get().isRunning()) return;
        final Context app = ctx.getApplicationContext();
        String[] modules = ModuleWishlist.keys(app);
        // Record the ordered batch so the install index can render a row per module (the queue only
        // reports the current module + remaining, not the full list).
        ModuleBatch.save(app, modules);
        Intent i = new Intent(app, InstallService.class);
        i.setAction(InstallService.ACTION_START_MODULES);
        i.putExtra(InstallService.EXTRA_MODULES, modules);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
        else app.startService(i);
        Log.i(TAG, "module drain: handed " + modules.length + " module(s) to InstallService");
        // Handed off; the module-queue owns the run from here. Wishlist cleared; the batch persists
        // until the index sees the run finish.
        ModuleWishlist.clear(app);
    }
}
