/*
 * ============================================================================
 * Name        : AddonsRefresh.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-99. The single "Update add-ons" flow, shared by the module detail button and the
 *               module action sheet row so neither duplicates it. Modeled on ForgejoRepoRefresh: it
 *               gates like the dashboard update (needs internet, then metered consent), shows minimal
 *               inline progress (a description, a live one-line output tail, an indeterminate bar and a
 *               Cancel) injected right after the trigger view, runs the box refresh on an IO thread, and
 *               reports the outcome in a snackbar.
 *
 *               Lifecycle: there is NO persistent app-side state. The box refresh job is detached
 *               (setsid), so a host that goes away mid-run just drops the UI updates (guarded by
 *               View.isAttachedToWindow()); the box finishes on its own and the gallery is swapped in
 *               only on success. The only state is the box's own status/pid files, which the box manages.
 * ============================================================================
 */
package org.appdevforall.k2go.addons.presentation;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.addons.data.AddonsRefreshClient;
import org.appdevforall.k2go.util.AppExecutors;
import org.appdevforall.k2go.util.Snackbars;

public final class AddonsRefresh {

    private AddonsRefresh() {}

    /**
     * Gate (internet, then metered consent) then run the refresh with progress injected right after
     * {@code trigger}. The trigger stays in place (only disabled) as a visible anchor for the snackbar.
     */
    public static void start(@NonNull Activity act, @NonNull View trigger) {
        if (!org.appdevforall.k2go.networkpolicy.data.AndroidNetworkClassifier.hasInternet(act)) {
            Snackbars.make(trigger, act.getString(R.string.k2go_dash_needs_internet)).show();
            return;
        }
        // Same metered gate as the dashboard update: the box downloads the gallery over the device network.
        org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate.guardHeavyStart(act, () -> run(trigger));
    }

    private static void run(@NonNull View trigger) {
        final ViewGroup parent = (ViewGroup) trigger.getParent();
        if (parent == null || !trigger.isAttachedToWindow()) return;   // host went away during the gate
        final Context ctx = trigger.getContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final float d = ctx.getResources().getDisplayMetrics().density;
        final int side = Math.round(20 * d);

        final LinearLayout progress = new LinearLayout(ctx);
        progress.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = side; plp.rightMargin = side; plp.topMargin = Math.round(8 * d);
        progress.setLayoutParams(plp);

        final TextView label = new TextView(ctx);
        label.setText(R.string.k2go_code_addons_updating);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        label.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        progress.addView(label);

        final TextView liveLine = new TextView(ctx);
        liveLine.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        liveLine.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        liveLine.setMaxLines(1);
        liveLine.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Math.round(2 * d);
        liveLine.setLayoutParams(llp);
        progress.addView(liveLine);

        final LinearLayout barLine = new LinearLayout(ctx);
        barLine.setOrientation(LinearLayout.HORIZONTAL);
        barLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLineLp.topMargin = Math.round(4 * d);
        barLine.setLayoutParams(barLineLp);

        final LinearProgressIndicator bar = new LinearProgressIndicator(ctx);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.setLayoutParams(blp);
        barLine.addView(bar);

        final TextView cancel = new TextView(ctx);
        cancel.setText(R.string.k2go_dash_cancel);
        cancel.setAllCaps(true);
        cancel.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        cancel.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_teal));
        int hp = Math.round(12 * d), vp = Math.round(6 * d);
        cancel.setPadding(hp, vp, hp, vp);
        barLine.addView(cancel);
        progress.addView(barLine);

        parent.addView(progress, parent.indexOfChild(trigger) + 1);
        trigger.setEnabled(false);   // stays in place as an anchor; re-enabled when the refresh settles

        cancel.setOnClickListener(cv -> {
            cancel.setEnabled(false);
            label.setText(R.string.k2go_code_addons_update_cancelling);
            AppExecutors.get().io().execute(() -> new AddonsRefreshClient().cancel());
        });

        AppExecutors.get().io().execute(() -> {
            final AddonsRefreshClient client = new AddonsRefreshClient();
            final AddonsRefreshClient.Result r = client.refresh(rawLine -> {
                final String shown = rawLine.trim();
                main.post(() -> { if (liveLine.isAttachedToWindow()) liveLine.setText(shown); });
            });
            final int failed = client.lastFailed();
            final boolean upToDate = client.lastUpToDate();
            main.post(() -> {
                if (!trigger.isAttachedToWindow()) return;
                parent.removeView(progress);
                trigger.setEnabled(true);
                Snackbars.make(trigger, ctx.getString(messageFor(r, failed, upToDate))).show();
            });
        });
    }

    /** Map the refresh outcome to a user message covering every state. */
    private static int messageFor(AddonsRefreshClient.Result r, int failed, boolean upToDate) {
        if (r == AddonsRefreshClient.Result.CANCELLED) return R.string.k2go_code_addons_update_cancelled;
        if (r != AddonsRefreshClient.Result.DONE) return R.string.k2go_code_addons_update_failed;   // box unreachable
        if (failed > 0) return R.string.k2go_code_addons_update_some_failed;   // some files could not be fetched
        // K2GO-441: the box is the single source for "nothing changed" (result: up-to-date). A box that
        // does not report it (pre-1.3.9) ran the old full mirror, so "updated" is the correct default.
        if (upToDate) return R.string.k2go_code_addons_update_none;
        return R.string.k2go_code_addons_update_done;
    }
}
