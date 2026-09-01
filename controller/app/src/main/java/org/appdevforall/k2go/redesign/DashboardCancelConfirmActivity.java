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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.appdevforall.k2go.R;

public final class DashboardCancelConfirmActivity extends AppCompatActivity {

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // If the update already ended between tapping the notification and getting here, there is
        // nothing to cancel — just close without a dialog.
        if (!DashboardRebuildService.isRunning()) { finish(); return; }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.k2go_dash_cancel_confirm_title)
                .setMessage(R.string.k2go_dash_cancel_confirm_msg)
                .setNegativeButton(R.string.k2go_dash_cancel_confirm_keep, (d, w) -> finish())
                .setPositiveButton(R.string.k2go_dash_cancel_confirm_stop, (d, w) -> {
                    ContextCompat.startForegroundService(this, new Intent(this, DashboardRebuildService.class)
                            .setAction(DashboardRebuildService.ACTION_CANCEL));
                    finish();
                })
                .setOnCancelListener(d -> finish())   // tap-outside / back = keep updating
                .show();
    }
}
