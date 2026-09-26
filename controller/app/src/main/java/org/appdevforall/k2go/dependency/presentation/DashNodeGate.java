/*
 * ============================================================================
 * Name        : DashNodeGate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-430. Gate a module install on the box dash-node version. Two mechanisms:
 *               HARD block : a module that declares a minimum (DashNodeRequirement, e.g. Forgejo -> 1.3.7)
 *               cannot install on a box below it; show a dialog and route to the dashboard update.
 *               SOFT suggest : for a batch, when a dashboard update is available, recommend updating first
 *               (Update / Install anyway). Reuses the existing version read (DashboardVersion, from the
 *               rootfs, no network/proot) and the cached update-check (UpdateStatusCache), so the gate is
 *               synchronous.
 * ============================================================================
 */
package org.appdevforall.k2go.dependency.presentation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.dependency.domain.DashNodeRequirement;
import org.appdevforall.k2go.redesign.DashboardVersion;
import org.appdevforall.k2go.redesign.OpReturnNavigator;
import org.appdevforall.k2go.redesign.UpdateStatusCache;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

public final class DashNodeGate {
    private DashNodeGate() {}

    /** True when the box dash-node is below {@code moduleKey}'s hard minimum (null key / no minimum = false).
     *  A null (unreadable) installed version blocks a module that HAS a minimum: do not install it against
     *  a dashboard we cannot verify. */
    public static boolean installBlocked(@NonNull Context ctx, @Nullable String moduleKey) {
        int[] min = DashNodeRequirement.minFor(moduleKey);
        return min != null && !DashboardVersion.atLeast(DashboardVersion.installed(ctx), min[0], min[1], min[2]);
    }

    /** The first key below its dash-node minimum, or null. */
    @Nullable
    private static String firstBlocked(@NonNull Context ctx, @Nullable String[] keys) {
        if (keys == null) return null;
        for (String k : keys) if (installBlocked(ctx, k)) return k;
        return null;
    }

    /**
     * Gate a module install batch before it runs. If any banked module needs a newer dash-node than the
     * box has, block and route to the dashboard update. Otherwise, if a dashboard update is available,
     * suggest it first (Update / Install anyway). Otherwise run {@code onProceed} straight away.
     */
    public static void guardModuleBatch(@NonNull Activity act, @Nullable String[] bankedKeys, @NonNull Runnable onProceed) {
        String blocked = firstBlocked(act, bankedKeys);
        if (blocked != null) { showBlocked(act, blocked); return; }
        if (UpdateStatusCache.updateAvailable(act)) { showSuggest(act, onProceed); return; }
        onProceed.run();
    }

    /**
     * Gate a single-module (re)install, e.g. a retry: returns false (and shows the block dialog) when the
     * module is below its dash-node minimum, true when it may proceed. Hard block only (a retry does not
     * carry the soft update suggestion). Covers the install paths that start the index directly, not via
     * the batch gate.
     */
    public static boolean allowInstall(@NonNull Context ctx, @Nullable String moduleKey) {
        if (installBlocked(ctx, moduleKey)) { showBlocked(ctx, moduleKey); return false; }
        return true;
    }

    private static void showBlocked(@NonNull Context ctx, @NonNull String moduleKey) {
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_dep_required_title)
                .setMessage(ctx.getString(R.string.k2go_dep_required_msg_fmt, DashNodeRequirement.minLabel(moduleKey)))
                .setPositive(R.string.k2go_dep_update_dashboard, BrandDialog.Role.PRIMARY, () -> openDashboard(ctx))
                .setNegative(android.R.string.cancel, null)
                .show();
    }

    private static void showSuggest(@NonNull Context ctx, @NonNull Runnable onProceed) {
        new BrandDialog(ctx)
                .setTitle(R.string.k2go_dep_suggest_title)
                .setMessage(R.string.k2go_dep_suggest_msg)
                .setPositive(R.string.k2go_dep_update_dashboard, BrandDialog.Role.PRIMARY, () -> openDashboard(ctx))
                .setNegative(R.string.k2go_dep_install_anyway, onProceed::run)
                // The suggestion is optional: dismissing it (back / tap-outside) proceeds with the install
                // the user already asked for, rather than silently swallowing it.
                .setOnCancel(onProceed::run)
                .show();
    }

    /** Open the dashboard detail (the Rebuild/Update card) so the user can update the dash-node. Reuses the
     *  canonical navigation intent instead of rebuilding it here. */
    private static void openDashboard(@NonNull Context ctx) {
        Intent i = OpReturnNavigator.dashboardRebuild(ctx);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
