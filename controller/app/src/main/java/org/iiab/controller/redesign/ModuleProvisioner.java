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
import org.iiab.controller.system.data.SystemDoor;
import org.iiab.controller.system.domain.OperationDispatcher;

public final class ModuleProvisioner {
    private ModuleProvisioner() {}

    private static final String TAG = "K2Go-Provision";

    /** True when there are modules scheduled to install. */
    public static boolean hasPending(Context ctx) {
        return ModuleWishlist.has(ctx);
    }

    /**
     * Hand the scheduled modules to the module-queue engine.
     *
     * <p>Requires the rootfs; the queue owns server stop/restart + progress from here.
     *
     * @return the model's verdict, or {@code null} when the drain was not attempted at all —
     *         nothing is banked, or a queue is already running. ADFA-5061: the caller needs the
     *         three cases apart. A refusal leaves the order banked, so the caller cannot infer
     *         "it started" from "the wishlist is still there", and an orchestrator that keeps
     *         asking a door that keeps saying no will ask forever.
     */
    public static OperationDispatcher.Dispatch drain(Context ctx) {
        if (!ModuleWishlist.has(ctx)) return null;
        if (ModuleQueueRepository.get().isRunning()) return null;
        final Context app = ctx.getApplicationContext();
        String[] modules = ModuleWishlist.keys(app);
        if (modules.length == 0) {
            // has() said yes and keys() said no — a corrupted preference, not a state of the box.
            // Answered UNAVAILABLE rather than null so the caller retires the stage instead of
            // waiting for a batch that will never arrive.
            Log.w(TAG, "module drain: the wishlist says pending but holds no keys");
            return OperationDispatcher.Dispatch.UNAVAILABLE;
        }
        // ADFA-5061: ask the model before handing work to the engine. The two checks above are
        // about this class's own bookkeeping; neither is about the box. Without this a drain
        // would start runroles over a half-installed rootfs that cannot boot, and over a system
        // that does not exist yet. The wishlist is deliberately left alone on a refusal — the
        // order stays banked and the next drain takes it.
        OperationDispatcher.Dispatch verdict = SystemDoor.dispatch(app, modules[0]);
        if (!OperationDispatcher.mayRunStopped(verdict)) {
            Log.i(TAG, "module drain: held, the box says " + verdict
                    + " (" + modules.length + " module(s) still banked)");
            return verdict;
        }
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
        return verdict;
    }
}
