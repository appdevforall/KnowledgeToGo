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
package org.iiab.controller.redesign;

import android.content.Context;
import android.view.View;

import java.util.Collections;

import org.iiab.controller.env.EnvironmentLock;
import org.iiab.controller.install.presentation.InstallService;
import org.iiab.controller.util.BusyMessage;
import org.iiab.controller.util.Snackbars;

public final class ModuleRetry {

    private ModuleRetry() {}

    /**
     * Re-fire the install of a single module that failed. Gated by the environment lock: if something
     * else already owns the rootfs, show a busy snackbar anchored on {@code anchor} and do nothing.
     *
     * @return true if the retry was actually started (caller may navigate on that), false if it was
     *         swallowed by the busy gate or the inputs were null.
     */
    public static boolean fire(View anchor, String moduleKey) {
        if (anchor == null || moduleKey == null) return false;
        Context ctx = anchor.getContext();
        if (EnvironmentLock.isHeld(ctx)) {
            Snackbars.make(anchor, BusyMessage.resFor(ctx)).show();
            return false;
        }
        InstallService.retryModules(ctx, Collections.singletonList(moduleKey));
        return true;
    }
}
