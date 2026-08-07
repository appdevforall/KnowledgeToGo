/*
 * ============================================================================
 * Name        : DashboardRebuild.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5011 / ADFA-5051. Single entry point for starting a dash-node REST-core rebuild,
 *               shared by the Module-management hub row and the dashboard detail card. Gated flow:
 *               busy check (EnvironmentLock) -> internet check -> confirm dialog -> start.
 *
 *               ADFA-5051 — version-gated cutover: from dash-node 1.2.0 the core updates ITSELF LIVE
 *               over REST (POST /system/dashboard/rebuild: the blue-green rebuild that stages, smoke-
 *               tests, atomically swaps and rolls back on failure — no rootfs/proot). Installs still
 *               on < 1.2.0 predate that, so they take the heavier proot rebuild (InstallService) once
 *               to reach 1.2.0; from there every later update is the live REST path.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.iiab.controller.R;
import org.iiab.controller.install.presentation.InstallService;
import org.iiab.controller.util.AppExecutors;
import org.iiab.controller.util.Snackbars;

public final class DashboardRebuild {
    private DashboardRebuild() {}

    // Live-rebuild poll cadence + overall cap. The blue-green rebuild does yarn install/build + smoke
    // test + a dash-node restart, so allow a generous ceiling; the restart briefly makes the status
    // endpoint unreachable, which we treat as "keep polling", not a failure.
    private static final long POLL_MS = 2500L;
    private static final int MAX_POLLS = 160;   // ~6.5 min

    /** Gate then confirm then start. {@code anchor} is where a "busy"/"no internet" snackbar shows.
     *  {@code onLiveUpdated} (nullable) runs after a successful LIVE (REST) update so the caller can
     *  refresh its version chip / update pill in place (ADFA-5051). */
    public static void confirmAndStart(@NonNull Fragment host, @NonNull View anchor,
                                       @Nullable Runnable onLiveUpdated) {
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
                .setPositiveButton(R.string.k2go_dash_rebuild, (d, w) -> start(host, anchor, onLiveUpdated))
                .show();
    }

    /** ADFA-5051: route by the installed dash-node version. >= 1.2.0 updates live over REST; older
     *  installs take the proot rebuild once as a bridge to 1.2.0. The version read hits disk, so it
     *  runs off the main thread; the routing itself is posted back to the UI. */
    private static void start(@NonNull Fragment host, @NonNull View anchor, @Nullable Runnable onLiveUpdated) {
        final Context app = host.requireContext().getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        AppExecutors.get().io().execute(() -> {
            final boolean rest = DashboardVersion.atLeast(DashboardVersion.installed(app), 1, 2, 0);
            main.post(() -> {
                if (!host.isAdded()) return;
                if (rest) startRest(host, anchor, onLiveUpdated);
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

    /** ADFA-5051: live REST update. Trigger the in-server rebuild, then show a non-cancelable progress
     *  dialog that polls the state until done/error (tolerating the brief restart window). */
    private static void startRest(@NonNull Fragment host, @NonNull View anchor, @Nullable Runnable onLiveUpdated) {
        Context ctx = host.requireContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(24 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        CircularProgressIndicator spin = new CircularProgressIndicator(ctx);
        spin.setIndeterminate(true);
        box.addView(spin);
        TextView msg = new TextView(ctx);
        msg.setText(R.string.k2go_dash_live_running);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.leftMargin = pad;
        msg.setLayoutParams(mlp);
        box.addView(msg);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.k2go_dash_live_title)
                .setView(box)
                .setCancelable(false)
                .show();

        final Handler poller = new Handler(Looper.getMainLooper());
        DashboardClient.rebuildStart(new DashboardClient.RebuildStartCb() {
            @Override public void onStarted(boolean alreadyRunning) {
                if (!host.isAdded()) { safeDismiss(dialog); return; }
                pollStatus(host, dialog, poller, new int[]{0}, onLiveUpdated);
            }
            @Override public void onErr(String message) {
                safeDismiss(dialog);
                if (host.isAdded()) Snackbars.make(anchor, R.string.k2go_dash_live_start_failed).show();
            }
        });
    }

    /** Poll rebuild state until a terminal one, reusing a single {@code poller} Handler. A status error
     *  is transient (dash-node restarts mid-swap) so we keep polling until MAX_POLLS. Hitting the cap is
     *  NOT a failure: the rebuild runs detached server-side and may still finish, so we show a distinct
     *  "still working in the background" message rather than the rollback/failure one. */
    private static void pollStatus(@NonNull Fragment host, @NonNull AlertDialog dialog,
                                   @NonNull Handler poller, int[] tries, @Nullable Runnable onLiveUpdated) {
        if (!host.isAdded()) { safeDismiss(dialog); return; }
        if (tries[0]++ >= MAX_POLLS) { finishRest(host, dialog, R.string.k2go_dash_live_timeout, onLiveUpdated); return; }
        DashboardClient.rebuildStatus(new DashboardClient.RebuildStatusCb() {
            @Override public void onState(String state) {
                if (!host.isAdded()) { safeDismiss(dialog); return; }
                if ("done".equals(state)) { finishRest(host, dialog, R.string.k2go_dash_live_done, onLiveUpdated); return; }
                if ("error".equals(state)) { finishRest(host, dialog, R.string.k2go_dash_live_error, onLiveUpdated); return; }
                schedule();
            }
            @Override public void onErr(String message) {
                if (!host.isAdded()) { safeDismiss(dialog); return; }
                schedule();   // API likely restarting mid-swap; keep waiting
            }
            private void schedule() {
                poller.postDelayed(() -> pollStatus(host, dialog, poller, tries, onLiveUpdated), POLL_MS);
            }
        });
    }

    /** Swap the progress dialog for a simple result dialog carrying {@code msgRes} (done/error/timeout).
     *  On success, fire {@code onLiveUpdated} (ADFA-5051) so the caller refreshes its version/pill in
     *  place — otherwise the card keeps showing "update available" and users re-tap Rebuild. */
    private static void finishRest(@NonNull Fragment host, @NonNull AlertDialog dialog, int msgRes,
                                   @Nullable Runnable onLiveUpdated) {
        safeDismiss(dialog);
        if (!host.isAdded()) return;
        if (msgRes == R.string.k2go_dash_live_done && onLiveUpdated != null) onLiveUpdated.run();
        new MaterialAlertDialogBuilder(host.requireContext())
                .setTitle(R.string.k2go_dash_live_title)
                .setMessage(msgRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void safeDismiss(@NonNull AlertDialog d) {
        try { if (d.isShowing()) d.dismiss(); } catch (Exception ignore) { /* activity gone */ }
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
