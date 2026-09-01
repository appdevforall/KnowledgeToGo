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
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.appdevforall.k2go.install.presentation.InstallService;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;
import org.appdevforall.k2go.system.data.SystemDoor;
import org.appdevforall.k2go.system.domain.OperationDispatcher;

public final class MapsProvisioner {
    private MapsProvisioner() {}

    private static final String TAG = "K2Go-Provision";

    /** True when there is a banked maps selection waiting to be applied. */
    public static boolean hasPending(Context ctx) {
        return MapsWishlist.has(ctx);
    }

    /**
     * Hand the banked maps selection to the module-queue engine. Requires the rootfs (runs
     * runrole maps in the proot).
     *
     * @return the model's verdict, or {@code null} when the drain was not attempted — nothing
     *         banked, or a queue already running. See {@code ModuleProvisioner.drain}.
     */
    public static OperationDispatcher.Dispatch drain(Context ctx) {
        if (!MapsWishlist.has(ctx)) return null;
        if (ModuleQueueRepository.get().isRunning()) return null;
        final Context app = ctx.getApplicationContext();
        // ADFA-5061: same gate as ModuleProvisioner — see SystemDoor. Maps is the one runrole
        // that coexists with a live server, but "coexists with a live server" is not "runs over
        // a rootfs that will not boot", and that is the case this refuses. Selection kept.
        OperationDispatcher.Dispatch verdict = SystemDoor.dispatch(app, "maps");
        if (!OperationDispatcher.mayRunStopped(verdict)) {
            Log.i(TAG, "maps drain: held, the box says " + verdict + " (selection still banked)");
            return verdict;
        }
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
        return verdict;
    }
}
