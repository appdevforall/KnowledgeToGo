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

import android.content.Context;
import android.view.View;

import java.util.Collections;

import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.install.presentation.InstallService;
import org.appdevforall.k2go.system.data.SystemDoor;
import org.appdevforall.k2go.system.domain.OperationDispatcher;
import org.appdevforall.k2go.util.BusyMessage;
import org.appdevforall.k2go.util.Snackbars;

public final class ModuleRetry {

    private ModuleRetry() {}

    /**
     * Re-fire the install of a single module that failed. Two gates, the same the wishlist drain uses:
     * the environment lock (something else owns the rootfs) and the system door (the box may run a
     * stopped-class op right now). Either refusal shows a busy snackbar on {@code anchor} and does
     * nothing.
     *
     * @return true if the retry was actually started (caller may navigate on that), false if a gate
     *         swallowed it or the inputs were null.
     */
    public static boolean fire(View anchor, String moduleKey) {
        if (anchor == null || moduleKey == null) return false;
        Context ctx = anchor.getContext();
        if (EnvironmentLock.isHeld(ctx)) {
            Snackbars.make(anchor, BusyMessage.resFor(ctx)).show();
            return false;
        }
        // ADFA-4898: ask the same door the wishlist drain asks before starting stopped-class runroles —
        // the box must be in a state that may run them (not NO_SYSTEM / DAMAGED / mid-op). This is the
        // gate's only correct home: it is a PRE-FLIGHT check. It cannot move into the service loop,
        // because by then InstallGuard has planted the in-progress marker and the door would read our
        // own install as "installing" and refuse itself. A retry runs before that begin, so the read is
        // clean; and a single small file read on a deliberate tap is within the per-screen I/O budget.
        if (!OperationDispatcher.mayRunStopped(SystemDoor.dispatch(ctx, moduleKey))) {
            Snackbars.make(anchor, BusyMessage.resFor(ctx)).show();
            return false;
        }
        InstallService.retryModules(ctx, Collections.singletonList(moduleKey));
        return true;
    }
}
