/*
 * ============================================================================
 * Name        : DashboardRebuild.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5011. Single entry point for starting a dash-node REST-core rebuild, shared by
 *               the Module-management hub row and the dashboard detail card so both offer the exact
 *               same gated flow: busy check (EnvironmentLock) -> internet check -> confirm dialog ->
 *               start the guarded InstallService rebuild + open the progress screen (which now stays
 *               put until the rebuild reaches SUCCESS/FAILED).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.iiab.controller.R;
import org.iiab.controller.install.presentation.InstallService;
import org.iiab.controller.util.Snackbars;

public final class DashboardRebuild {
    private DashboardRebuild() {}

    /** Gate then confirm then start. {@code anchor} is where a "busy"/"no internet" snackbar shows. */
    public static void confirmAndStart(@NonNull Fragment host, @NonNull View anchor) {
        Context ctx = host.requireContext();
        if (org.iiab.controller.env.EnvironmentLock.isHeld(ctx)) {
            Snackbars.make(anchor, R.string.k2go_install_busy).show();
            return;
        }
        if (!hasInternet(ctx)) {
            Snackbars.make(anchor, R.string.k2go_dash_needs_internet).show();
            return;
        }
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.k2go_dash_rebuild_confirm_title)
                .setMessage(R.string.k2go_dash_rebuild_confirm_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.k2go_dash_rebuild, (d, w) -> start(host))
                .show();
    }

    /** Kick the foreground rebuild service and open the guarded progress screen (flagged as a rebuild
     *  so it stays on the animation and blocks leaving until the rebuild finishes). */
    private static void start(@NonNull Fragment host) {
        Context ctx = host.requireContext();
        Intent svc = new Intent(ctx, InstallService.class)
                .setAction(InstallService.ACTION_REBUILD_DASHBOARD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc);
        else ctx.startService(svc);
        host.startActivity(new Intent(ctx, SetupProgressActivity.class)
                .putExtra(SetupProgressActivity.EXTRA_REBUILD, true));
    }

    /** True when the device reports an internet-capable active network. Unknown -> true (let the
     *  preflight decide), matching the previous inline check in ModuleHubFragment. */
    public static boolean hasInternet(@NonNull Context ctx) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        android.net.Network n = cm.getActiveNetwork();
        if (n == null) return false;
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
