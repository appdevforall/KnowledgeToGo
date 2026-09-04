/*
 * ============================================================================
 * Name        : DashboardCancelDialog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-385. The one "Stop the update?" confirm, shared by the in-card Cancel
 *               (DashboardDetailFragment) and the notification path (DashboardCancelConfirmActivity)
 *               so the same dialog is not built twice. Only the stop action differs, so it is passed
 *               in; the dialog itself lives here once.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

public final class DashboardCancelDialog {

    private DashboardCancelDialog() {}

    /**
     * Show the shared "Stop the update?" confirm. {@code onStop} runs only on a positive answer;
     * "Keep updating", Back and a scrim tap all just dismiss. Returns the {@link BrandDialog.Handle}
     * so the notification activity can close itself on any dismissal.
     */
    public static BrandDialog.Handle show(Context ctx, Runnable onStop) {
        return new BrandDialog(ctx)
                .setTitle(R.string.k2go_dash_cancel_confirm_title)
                .setMessage(R.string.k2go_dash_cancel_confirm_msg)
                .setPositive(R.string.k2go_dash_cancel_confirm_stop, () -> onStop.run())
                .setNegative(R.string.k2go_dash_cancel_confirm_keep, null)
                .show();
    }
}
