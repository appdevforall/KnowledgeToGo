/*
 * ============================================================================
 * Name        : DashboardCancelConfirmActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5339. Confirmation gate for cancelling a background dashboard update FROM THE
 *               NOTIFICATION. A notification action can't host a dialog, so its Cancel routes here — a
 *               windowless, transparent activity that shows the same "Stop the update?" dialog the
 *               card uses, and only on a positive answer signals DashboardRebuildService. Dismiss or
 *               "Keep updating" just finishes, leaving the update running. (The in-card Cancel shows
 *               the dialog inline in DashboardDetailFragment; this covers the notification path.)
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.ui.dialog.BrandDialog;

public final class DashboardCancelConfirmActivity extends AppCompatActivity {

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // If the update already ended between tapping the notification and getting here, there is
        // nothing to cancel — just close without a dialog.
        if (!DashboardRebuildService.isRunning()) { finish(); return; }
        BrandDialog.Handle h = DashboardCancelDialog.show(this, () ->
                ContextCompat.startForegroundService(getApplicationContext(),
                        new Intent(getApplicationContext(), DashboardRebuildService.class)
                                .setAction(DashboardRebuildService.ACTION_CANCEL)));
        // Any dismissal — Stop, Keep updating, Back or a scrim tap — closes this windowless activity.
        h.getDialog().setOnDismissListener(d -> finish());
    }
}
