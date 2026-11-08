/*
 * ============================================================================
 * Name        : DashUpdateFailureDialog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-557. The IN-APP "dashboard update failed" notice, replacing the old system
 *               notification. Shown two ways from one guarded helper: the instant pop while the user
 *               watches the card (STATE_ERROR broadcast), and a re-show on the next foreground when the
 *               failure happened with no screen watching (maybeShow, driven by the persisted flag in
 *               UpdateStatusCache). Any dismissal acknowledges the failure (clears the flag), so it shows
 *               once per failure and never nags. Mirrors LibraryActivity.showDamagedDialog.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.presentation;

import android.app.Activity;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import org.appdevforall.k2go.LogRepository;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.feedback.domain.FeedbackType;
import org.appdevforall.k2go.feedback.presentation.FeedbackFab;
import org.appdevforall.k2go.redesign.OpReturnNavigator;
import org.appdevforall.k2go.redesign.UpdateStatusCache;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

import java.lang.ref.WeakReference;
import java.util.List;

public final class DashUpdateFailureDialog {
    private DashUpdateFailureDialog() {}

    // One dialog at a time across the app: the instant pop (dashboard) and the app-wide re-show
    // (ForegroundActivityHolder) both route here, so guard against a second copy stacking on top.
    private static WeakReference<BrandDialog.Handle> showing;

    private static boolean isShowing() {
        BrandDialog.Handle h = showing == null ? null : showing.get();
        return h != null && h.getDialog() != null && h.getDialog().isShowing();
    }

    /**
     * Re-show a pending failure on any foreground screen (the app-wide path). No-op when nothing is
     * pending or a copy is already up. The primary action opens the dashboard, where Rebuild retries.
     */
    public static void maybeShow(@NonNull Activity act) {
        if (!UpdateStatusCache.pendingFailure(act)) return;
        show(act, R.string.k2go_dash_update_open_dashboard, () -> openDashboard(act));
    }

    /**
     * Show the failure dialog now. {@code primaryText}/{@code onPrimary} are the primary action: "Try
     * again" in place on the dashboard card, or "Open dashboard" from any other screen. The reason line is
     * the same for every failure ("the previous version was kept", which holds whether the build failed or
     * never started), so it is not threaded through: one accurate message, one source.
     */
    public static void show(@NonNull Activity act, @StringRes int primaryText, @NonNull Runnable onPrimary) {
        if (act.isFinishing() || act.isDestroyed() || isShowing()) return;
        CharSequence message = act.getString(R.string.k2go_dash_live_error) + "\n\n"
                + act.getString(R.string.k2go_dash_update_failed_text);
        // Theme-wrap so the Material dialog inflates even if the host Activity's own theme is not Material.
        BrandDialog.Handle h = new BrandDialog(new ContextThemeWrapper(act, R.style.Theme_K2Go))
                .setIcon(R.drawable.ic_error_outline_24, R.color.k2go_clay)
                .setTitle(R.string.k2go_dash_update_failed_title)
                .setMessage(message)
                .setPositive(primaryText, BrandDialog.Role.PRIMARY, () -> { ack(act); onPrimary.run(); })
                .setNegative(R.string.k2go_damaged_close, () -> ack(act))
                // Report sends the APK log tail and keeps the dialog up (box-side log is deferred, K2GO-557).
                .setNeutral(R.string.k2go_damaged_report, () -> FeedbackFab.sendFeedback(
                        act, "dash-update-failed", FeedbackType.BUG, report()))
                .setDismissOnNeutral(false)
                .setOnCancel(() -> ack(act))
                .show();
        showing = new WeakReference<>(h);
    }

    /** Acknowledge: a failure the user has now seen is no longer pending. */
    private static void ack(@NonNull Activity act) {
        UpdateStatusCache.setPendingFailure(act, false);
    }

    private static void openDashboard(@NonNull Activity act) {
        act.startActivity(OpReturnNavigator.dashboardRebuild(act));
    }

    /** K2GO-557: the APK-side log tail, as the body of a report. Box-side log is a later ticket. */
    private static String report() {
        List<String> all = LogRepository.get().snapshot();
        int from = Math.max(0, all.size() - 80);
        StringBuilder sb = new StringBuilder("Dashboard update did not finish. Last lines:\n");
        for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
        return sb.toString();
    }
}
