/*
 * ============================================================================
 * Name        : DashboardRebuild.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5011 / ADFA-5051 / ADFA-5333. Single entry point for starting a dash-node REST-core
 *               rebuild, shared by the Module-management hub row and the dashboard detail card. Gated
 *               flow: busy check (EnvironmentLock) -> internet check -> confirm dialog -> start.
 *
 *               ADFA-5051 — version-gated cutover: from dash-node 1.2.0 the core updates ITSELF LIVE
 *               over REST (POST /system/dashboard/rebuild: the blue-green rebuild that stages, smoke-
 *               tests, atomically swaps and rolls back on failure — no rootfs/proot). Installs still
 *               on < 1.2.0 predate that, so they take the heavier proot rebuild (InstallService) once
 *               to reach 1.2.0; from there every later update is the live REST path.
 *
 *               ADFA-5333 — the LIVE path now runs in the BACKGROUND: it hands off to
 *               DashboardRebuildService (foreground notification) instead of trapping the user behind a
 *               non-cancelable modal. Completion is the server's signal (done/error), not a time cap, so
 *               there is no "taking longer than usual" timeout; a visible card refreshes on the
 *               service's completion broadcast. The proot path (< 1.2.0) is unchanged.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.iiab.controller.R;
import org.iiab.controller.install.presentation.InstallService;
import org.iiab.controller.system.domain.Operation;
import org.iiab.controller.util.AppExecutors;
import org.iiab.controller.util.Snackbars;

public final class DashboardRebuild {
    private DashboardRebuild() {}

    /** Gate then confirm then start. {@code anchor} is where the "busy"/"no internet"/"started"
     *  snackbars show. A visible dashboard card refreshes itself on
     *  {@link DashboardRebuildService#ACTION_STATE} (ADFA-5333), so there is no completion callback to
     *  pass here — the background update outlives this fragment. */
    public static void confirmAndStart(@NonNull Fragment host, @NonNull View anchor) {
        Context ctx = host.requireContext();
        if (org.iiab.controller.env.EnvironmentLock.isHeld(ctx)) {
            Snackbars.make(anchor, org.iiab.controller.util.BusyMessage.resFor(ctx)).show();
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
                .setPositiveButton(R.string.k2go_dash_rebuild, (d, w) -> start(host, anchor))
                .show();
    }

    /** ADFA-5051: route by the installed dash-node version. >= 1.2.0 updates live over REST; older
     *  installs take the proot rebuild once as a bridge to 1.2.0. The version read hits disk, so it
     *  runs off the main thread; the routing itself is posted back to the UI. */
    private static void start(@NonNull Fragment host, @NonNull View anchor) {
        final Context app = host.requireContext().getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        AppExecutors.get().io().execute(() -> {
            // ADFA-5062: the rebuild's execution class genuinely depends on state (the installed
            // dash-node version), so resolve the class first and build the Operation with it — the
            // pattern Operation's contract prescribes for a state-dependent class — instead of
            // forking on a bare boolean. dash-node >= 1.2.0 rebuilds itself LIVE over REST; older
            // installs take the STOPPED proot rebuild once as a bridge to 1.2.0.
            final Operation.ExecutionClass cls =
                    DashboardVersion.atLeast(DashboardVersion.installed(app), 1, 2, 0)
                            ? Operation.ExecutionClass.LIVE
                            : Operation.ExecutionClass.STOPPED;
            final Operation op = Operation.of("dashboard", Operation.Kind.APP_INSTALL, cls);
            main.post(() -> {
                if (!host.isAdded()) return;
                if (op.isLive()) startRest(host, anchor);
                else startProot(host);
            });
        });
    }

    /** Kick the foreground proot rebuild service and open the guarded progress screen (flagged as a
     *  rebuild so it stays on the animation and blocks leaving until the rebuild finishes). */
    private static void startProot(@NonNull Fragment host) {
        Context ctx = host.requireContext();
        Intent svc = new Intent(ctx, InstallService.class)
                .setAction(InstallService.ACTION_REBUILD_DASHBOARD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc);
        else ctx.startService(svc);
        host.startActivity(new Intent(ctx, SetupProgressActivity.class)
                .putExtra(SetupProgressActivity.EXTRA_REBUILD, true));
    }

    /** ADFA-5333: live REST update in the background. Hand off to DashboardRebuildService (foreground
     *  notification) and let the user go — the service POSTs the rebuild and polls the box until it
     *  reports done/error, with no time cap. A visible dashboard card refreshes on the service's
     *  completion broadcast; nothing pins this screen. */
    private static void startRest(@NonNull Fragment host, @NonNull View anchor) {
        DashboardRebuildService.start(host.requireContext().getApplicationContext());
        if (host.isAdded()) Snackbars.make(anchor, R.string.k2go_dash_update_started).show();
    }

    /** ADFA-5333: reverse gate for LIVE content downloads (ZIM/Books/Kolibri). Those run on the server
     *  and don't consult EnvironmentLock, so they need an explicit check: a dashboard update in flight
     *  restarts dash-node and would break an in-flight download. Returns true (and shows a refusal on
     *  {@code anchor}) when a download must NOT start now. Deep-env ops don't need this — they read the
     *  same fact through EnvironmentLock.isHeld (Holder.DASHBOARD). */
    public static boolean blockedByUpdate(@NonNull View anchor) {
        if (DashboardRebuildService.isRunning()) {
            Snackbars.make(anchor, R.string.k2go_busy_dashboard).show();
            return true;
        }
        return false;
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
