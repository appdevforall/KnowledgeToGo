/*
 * ============================================================================
 * Name        : CatalogUpdateRow.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-580. The Get more "Update catalogs" row: the only way the catalogs
 *               refresh. One tap refreshes every catalog through the metered-data gate
 *               (no prompt on Wi-Fi, a prompt on mobile data); a second tap cancels.
 *               State comes from WorkManager (the refresh jobs' tag), so the row owns no flag.
 * ============================================================================
 */
package org.appdevforall.k2go.catalog.presentation;

import android.content.Context;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler;
import org.appdevforall.k2go.catalog.data.CatalogRefreshStore;
import org.appdevforall.k2go.kolibri.data.CatalogRepositoryImpl;
import org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate;
import org.appdevforall.k2go.redesign.KiwixCatalog;

public final class CatalogUpdateRow {

    private CatalogUpdateRow() {}

    /** Wire {@code button} and {@code status} for the life of the fragment's view. */
    public static void bind(@NonNull Fragment f, @NonNull TextView status, @NonNull Button button) {
        final Context app = f.requireContext().getApplicationContext();
        final boolean[] running = { false };
        WorkManager.getInstance(app).getWorkInfosByTagLiveData(CatalogRefreshScheduler.TAG)
                .observe(f.getViewLifecycleOwner(), infos -> {
                    running[0] = false;
                    if (infos != null) {
                        for (WorkInfo i : infos) if (!i.getState().isFinished()) running[0] = true;
                    }
                    button.setText(running[0] ? R.string.k2go_dash_cancel : R.string.k2go_gm_catalogs_update);
                    status.setText(running[0] ? app.getString(R.string.k2go_gm_catalogs_updating) : checkedText(app));
                });
        button.setOnClickListener(v -> {
            if (running[0]) {
                CatalogRefreshScheduler.cancelAll(app);
                return;
            }
            NetworkPolicyGate.guardThisAction(f.requireActivity(), () -> {
                KiwixCatalog.forceRefresh(app);
                CatalogRepositoryImpl.refresh(app);
            }, () -> {});
        });
    }

    private static String checkedText(Context app) {
        long ms = new CatalogRefreshStore(app).lastCheckAnyMs();
        if (ms <= 0) return app.getString(R.string.k2go_gm_catalogs_bundled);
        return app.getString(R.string.k2go_gm_catalogs_checked,
                DateUtils.formatDateTime(app, ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
    }
}
