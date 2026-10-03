/*
 * ============================================================================
 * Name        : CodeAssetsRefresh.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-437 / K2GO-443. The single "Update build assets" flow, shared by the module detail
 *               button and the module action sheet row. It gates like the dashboard update (needs
 *               internet, then metered consent), then drives the durable job engine through
 *               CodeAssetsDownloadService: a determinate progress bar (percent + speed) with Pause /
 *               Resume and Cancel, injected right after the trigger view. The download runs in a
 *               foreground service and on the box, so it survives this view going away: re-opening the
 *               sheet re-attaches to the running session.
 *
 *               Lifecycle: no persistent app-side state here. Pause/resume/cancel state lives in the
 *               session + the box job; this view only observes it and re-renders. A terminal state
 *               (done / failed / cancelled) removes the inline UI and reports it in a snackbar.
 * ============================================================================
 */
package org.appdevforall.k2go.codeassets.presentation;

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
import org.appdevforall.k2go.util.ByteFormatter;
import org.appdevforall.k2go.util.Snackbars;

public final class CodeAssetsRefresh {

    private CodeAssetsRefresh() {}

    /**
     * Gate (internet, then metered consent) then run the update with progress injected right after
     * {@code trigger}. The trigger stays in place (only disabled) as a visible anchor for the snackbar.
     */
    public static void start(@NonNull Activity act, @NonNull View trigger) {
        if (!org.appdevforall.k2go.networkpolicy.data.AndroidNetworkClassifier.hasInternet(act)) {
            Snackbars.make(trigger, act.getString(R.string.k2go_dash_needs_internet)).show();
            return;
        }
        // Same metered gate as the dashboard update: the box downloads the build assets over the device network.
        org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate.guardHeavyStart(act, () -> run(trigger));
    }

    private static void run(@NonNull View trigger) {
        final ViewGroup parent = (ViewGroup) trigger.getParent();
        if (parent == null || !trigger.isAttachedToWindow()) return;   // host went away during the gate
        final Context ctx = trigger.getContext().getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final float d = trigger.getResources().getDisplayMetrics().density;
        final int side = Math.round(20 * d);

        final LinearLayout progress = new LinearLayout(trigger.getContext());
        progress.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = side; plp.rightMargin = side; plp.topMargin = Math.round(8 * d);
        progress.setLayoutParams(plp);

        final TextView label = new TextView(trigger.getContext());
        label.setText(R.string.k2go_code_assets_updating);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        label.setTextColor(ContextCompat.getColor(trigger.getContext(), R.color.k2go_muted));
        progress.addView(label);

        final TextView statusLine = new TextView(trigger.getContext());
        statusLine.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        statusLine.setTextColor(ContextCompat.getColor(trigger.getContext(), R.color.k2go_muted));
        statusLine.setMaxLines(1);
        statusLine.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Math.round(2 * d);
        statusLine.setLayoutParams(slp);
        progress.addView(statusLine);

        final LinearLayout barLine = new LinearLayout(trigger.getContext());
        barLine.setOrientation(LinearLayout.HORIZONTAL);
        barLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLineLp.topMargin = Math.round(4 * d);
        barLine.setLayoutParams(barLineLp);

        final LinearProgressIndicator bar = new LinearProgressIndicator(trigger.getContext());
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.setLayoutParams(blp);
        barLine.addView(bar);

        final int hp = Math.round(12 * d), vp = Math.round(6 * d);
        final TextView pauseBtn = new TextView(trigger.getContext());
        pauseBtn.setText(R.string.k2go_dl_pause);
        pauseBtn.setAllCaps(true);
        pauseBtn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        pauseBtn.setTextColor(ContextCompat.getColor(trigger.getContext(), R.color.k2go_teal));
        pauseBtn.setPadding(hp, vp, hp, vp);
        barLine.addView(pauseBtn);

        final TextView cancelBtn = new TextView(trigger.getContext());
        cancelBtn.setText(R.string.k2go_dash_cancel);
        cancelBtn.setAllCaps(true);
        cancelBtn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        cancelBtn.setTextColor(ContextCompat.getColor(trigger.getContext(), R.color.k2go_teal));
        cancelBtn.setPadding(hp, vp, hp, vp);
        barLine.addView(cancelBtn);
        progress.addView(barLine);

        parent.addView(progress, parent.indexOfChild(trigger) + 1);
        trigger.setEnabled(false);   // stays in place as an anchor; re-enabled when the update settles

        pauseBtn.setOnClickListener(v -> {
            if (CodeAssetsDownloadService.isPaused()) CodeAssetsDownloadService.resume(ctx);
            else CodeAssetsDownloadService.pause(ctx);
        });
        cancelBtn.setOnClickListener(v -> {
            cancelBtn.setEnabled(false);
            CodeAssetsDownloadService.cancel(ctx);
        });

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            if (!trigger.isAttachedToWindow()) {   // sheet dismissed: stop observing, let the service run on
                CodeAssetsDownloadService.setListener(null);
                return;
            }
            if (CodeAssetsDownloadService.isComplete()) {
                finish(trigger, parent, progress, R.string.k2go_code_assets_update_done);
                return;
            }
            if (CodeAssetsDownloadService.hasFailed() && !CodeAssetsDownloadService.isRunning()) {
                finish(trigger, parent, progress, R.string.k2go_code_assets_update_failed);
                return;
            }
            if (!CodeAssetsDownloadService.hasSession()) {   // cancelled (purged); done is caught above
                finish(trigger, parent, progress, R.string.k2go_code_assets_update_cancelled);
                return;
            }
            final boolean paused = CodeAssetsDownloadService.isPaused();
            final int pct = CodeAssetsDownloadService.percent();
            bar.setIndeterminate(pct < 0);
            if (pct >= 0) bar.setProgressCompat(pct, true);
            pauseBtn.setText(paused ? R.string.k2go_dl_resume : R.string.k2go_dl_pause);
            if (CodeAssetsDownloadService.reconnectAttempt() > 0) {
                statusLine.setText(R.string.k2go_retrying);
            } else if (paused) {
                statusLine.setText(R.string.k2go_dl_paused);
            } else {
                final long spd = CodeAssetsDownloadService.speed();
                statusLine.setText(spd > 0
                        ? ByteFormatter.toHuman(spd) + "/s"
                        : trigger.getContext().getString(R.string.k2go_code_assets_updating));
            }
        };

        CodeAssetsDownloadService.setListener(() -> main.post(render[0]));
        CodeAssetsDownloadService.start(ctx);
        main.post(render[0]);   // initial paint
    }

    private static void finish(View trigger, ViewGroup parent, View progress, int msgRes) {
        CodeAssetsDownloadService.setListener(null);
        if (progress.getParent() == parent) parent.removeView(progress);
        trigger.setEnabled(true);
        if (trigger.isAttachedToWindow()) {
            Snackbars.make(trigger, trigger.getContext().getString(msgRes)).show();
        }
        CodeAssetsDownloadService.finishSession();
    }
}
