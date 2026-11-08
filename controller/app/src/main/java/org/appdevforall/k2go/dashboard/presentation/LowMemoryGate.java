/*
 * ============================================================================
 * Name        : LowMemoryGate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-557. Pre-start in-app gate for the dash-node live update. When the device is low on
 *               memory the update may fail, so warn BEFORE it starts and let the user close other apps
 *               first ("Not now") or proceed ("Start anyway"). Presentation only: the "is memory tight"
 *               rule stays pure in LowMemoryAdvice, the read in DeviceMemoryReader; this just turns that
 *               boolean into a dialog. Mirrors dependency.presentation.DashNodeGate.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.presentation;

import android.app.Activity;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.dashboard.data.DeviceMemoryReader;
import org.appdevforall.k2go.dashboard.domain.LowMemoryAdvice;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

public final class LowMemoryGate {
    private LowMemoryGate() {}

    /**
     * Run {@code onProceed} straight away when memory is fine. When it is tight, show the advisory and run
     * {@code onProceed} only if the user picks "Start anyway"; "Not now" (or dismissing) does nothing, so
     * the user can close apps and retry. Advisory, not a block: the choice is always the user's.
     */
    public static void guard(@NonNull Activity act, @NonNull Runnable onProceed) {
        if (!LowMemoryAdvice.shouldAdviseCloseApps(DeviceMemoryReader.read(act))) {
            onProceed.run();
            return;
        }
        new BrandDialog(new ContextThemeWrapper(act, R.style.Theme_K2Go))
                .setTitle(R.string.k2go_dash_lowmem_title)
                .setMessage(R.string.k2go_dash_lowmem_text)
                .setPositive(R.string.k2go_dash_lowmem_start_anyway, BrandDialog.Role.PRIMARY, onProceed::run)
                .setNegative(R.string.k2go_dash_lowmem_not_now, null)
                .show();
    }
}
