/*
 * ============================================================================
 * Name        : ModuleRetry.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4898. One place for "retry this one failed module". Both the live progress card
 *               (ModuleInstallFragment) and the module detail (ModuleDetailFragment) call this, so the
 *               user-confirmed, busy-gated, single-module retry is defined once and can't drift between
 *               surfaces. The actual re-fire is InstallService.retryModules; this only adds the busy
 *               gate + the single-key packaging the UI needs.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Collections;

import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.install.presentation.InstallService;
import org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate;
import org.appdevforall.k2go.system.data.SystemDoor;
import org.appdevforall.k2go.system.domain.OperationDispatcher;
import org.appdevforall.k2go.util.BusyMessage;
import org.appdevforall.k2go.util.Snackbars;

public final class ModuleRetry {

    private ModuleRetry() {}

    /** Same as {@link #fire(View, String, Runnable)} with no start callback. */
    public static void fire(View anchor, String moduleKey) {
        fire(anchor, moduleKey, null);
    }

    /**
     * Re-fire the install of a single module that failed. Two gates, the same the wishlist drain uses:
     * the environment lock (something else owns the rootfs) and the system door (the box may run a
     * stopped-class op right now). Either refusal shows a busy snackbar on {@code anchor} and does
     * nothing. K2GO-593: then the same mobile data question as "Install now", because a retry
     * downloads again.
     *
     * @param onStarted run on the main thread once the retry really starts (the caller may navigate
     *                  then); not run when a gate refuses or the user declines mobile data.
     */
    public static void fire(View anchor, String moduleKey, @Nullable Runnable onStarted) {
        if (anchor == null || moduleKey == null) return;
        Context ctx = anchor.getContext();
        if (EnvironmentLock.isHeld(ctx)) {
            Snackbars.make(anchor, BusyMessage.resFor(ctx)).show();
            return;
        }
        // ADFA-4898: ask the same door the wishlist drain asks before starting stopped-class runroles —
        // the box must be in a state that may run them (not NO_SYSTEM / DAMAGED / mid-op). This is the
        // gate's only correct home: it is a PRE-FLIGHT check. It cannot move into the service loop,
        // because by then InstallGuard has planted the in-progress marker and the door would read our
        // own install as "installing" and refuse itself. A retry runs before that begin, so the read is
        // clean; and a single small file read on a deliberate tap is within the per-screen I/O budget.
        if (!OperationDispatcher.mayRunStopped(SystemDoor.dispatch(ctx, moduleKey))) {
            Snackbars.make(anchor, BusyMessage.resFor(ctx)).show();
            return;
        }
        // K2GO-430: a retry starts the install directly (not via openModuleIndex), so gate the dash-node
        // dependency here too. If the box dash-node is below the module's minimum, the gate shows the
        // "update the dashboard first" dialog and the retry does not fire.
        if (!org.appdevforall.k2go.dependency.presentation.DashNodeGate.allowInstall(ctx, moduleKey)) {
            return;
        }
        final Runnable start = () -> {
            InstallService.retryModules(ctx, Collections.singletonList(moduleKey));
            if (onStarted != null) onStarted.run();
        };
        Activity act = activityOf(ctx);
        if (act == null) {   // no window to ask in: start only when no question is needed
            if (org.appdevforall.k2go.networkpolicy.data.NetworkCostAdmission.allowsHeavyStartNow(ctx)) start.run();
            return;
        }
        NetworkPolicyGate.guardHeavyStart(act, start);
    }

    @Nullable
    private static Activity activityOf(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }
}
