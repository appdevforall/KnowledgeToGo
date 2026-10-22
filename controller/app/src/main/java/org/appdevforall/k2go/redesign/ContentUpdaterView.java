/*
 * ============================================================================
 * Name        : ContentUpdaterView.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-503. The single "Update <module>" inline flow, shared by every content updater
 *               (add-ons, build assets, and any future download module) through the ContentUpdater
 *               contract. It gates like the dashboard update (needs internet, then metered consent),
 *               then drives the module's durable job: a determinate bar with percent + speed + total
 *               ETA, a "Checking for updates..." state while the box has no percent yet, Pause /
 *               Resume (when the module supports it) and Cancel, injected right after the trigger.
 *
 *               One owner for the four things each module used to copy: the progress block, the
 *               status-line text (so it never just repeats the title), the terminal message (done /
 *               already-up-to-date / failed / cancelled, read from the job), and the listener
 *               lifecycle. The lifecycle guard distinguishes a not-yet-started session from a purged
 *               one, so the first paint never reports a fresh start as "cancelled".
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

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

public final class ContentUpdaterView {

    private ContentUpdaterView() {}

    /**
     * Gate (internet, then metered consent) then run the update with progress injected right after
     * {@code trigger}. The trigger stays in place (only disabled) as a visible anchor for the snackbar.
     */
    public static void mount(@NonNull Activity act, @NonNull View trigger, @NonNull ContentUpdater u) {
        if (!org.appdevforall.k2go.networkpolicy.data.AndroidNetworkClassifier.hasInternet(act)) {
            Snackbars.make(trigger, act.getString(R.string.k2go_dash_needs_internet)).show();
            return;
        }
        // Same metered gate as the dashboard update: the box downloads the content over the device network.
        org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate.guardHeavyStart(act, () -> run(trigger, u));
    }

    private static void run(@NonNull View trigger, @NonNull ContentUpdater u) {
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
        label.setText(u.titleRes());
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
        statusLine.setText(R.string.k2go_update_checking);   // first paint before any job state
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
        if (u.supportsPauseResume()) {
            pauseBtn.setText(R.string.k2go_dl_pause);
            pauseBtn.setAllCaps(true);
            pauseBtn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            pauseBtn.setTextColor(ContextCompat.getColor(trigger.getContext(), R.color.k2go_teal));
            pauseBtn.setPadding(hp, vp, hp, vp);
            barLine.addView(pauseBtn);
        }

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
            if (u.isPaused()) u.resume(ctx);
            else u.pause(ctx);
        });
        cancelBtn.setOnClickListener(v -> {
            cancelBtn.setEnabled(false);
            u.cancel(ctx);
        });

        // Clear the session listener the moment this view leaves the window (sheet dismissed), so the
        // static session never keeps a destroyed Activity alive while a download runs on. Deterministic:
        // it does not wait for the next session event (a paused download emits none).
        final View.OnAttachStateChangeListener detach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(@NonNull View v) {}
            @Override public void onViewDetachedFromWindow(@NonNull View v) { u.setListener(null); }
        };

        // sawSession distinguishes "the session has not begun yet" (the service's onStartCommand runs
        // after this first paint) from "the session was purged" (a real cancel). Without it the first
        // paint reads a fresh start as cancelled and tears the UI down before it starts (K2GO-503).
        final boolean[] sawSession = { false };
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            if (!trigger.isAttachedToWindow()) { u.setListener(null); return; }
            final boolean running = u.isRunning();
            // hasFailed() BEFORE isComplete(): isComplete() is true for an all-FAILED session too
            // (FAILED is "not in progress"), so a failed run must be caught first.
            if (!running && u.hasFailed()) {
                terminal(u, trigger, parent, progress, detach, u.failedRes());
                return;
            }
            if (u.isComplete()) {
                terminal(u, trigger, parent, progress, detach,
                        u.lastWasUpToDate() ? u.upToDateRes() : u.doneRes());
                return;
            }
            if (!u.hasSession()) {
                if (sawSession[0]) {   // had a session, now gone: cancelled (purged)
                    terminal(u, trigger, parent, progress, detach, u.cancelledRes());
                } // else: not started yet, keep the indeterminate "checking" bar and wait
                return;
            }
            sawSession[0] = true;
            final boolean paused = u.isPaused();
            final int pct = u.percent();
            // "Checking for updates..." while the box stages/plans and no real progress has arrived
            // yet: the shared session starts at percent 0 and the box reports no bytes during staging,
            // so a bare "0%" would otherwise sit frozen. Any real byte flow (percent or speed) ends it.
            final boolean checking = pct <= 0 && (!u.supportsSpeedEta() || u.speed() <= 0);
            bar.setIndeterminate(checking);
            if (!checking && pct >= 0) bar.setProgressCompat(pct, true);
            if (u.supportsPauseResume()) pauseBtn.setText(paused ? R.string.k2go_dl_resume : R.string.k2go_dl_pause);
            if (u.reconnectAttempt() > 0) {
                statusLine.setText(R.string.k2go_retrying);
            } else if (paused) {
                statusLine.setText(R.string.k2go_dl_paused);
            } else if (checking) {
                statusLine.setText(R.string.k2go_update_checking);   // never repeat the title label
            } else {
                statusLine.setText(statusText(trigger.getContext(), u, pct));
            }
        };

        trigger.addOnAttachStateChangeListener(detach);
        // the session posts its updates to the main thread, so the listener runs on main: wire it directly.
        u.setListener(render[0]);
        u.start(ctx);
        main.post(render[0]);   // initial paint
    }

    /** "45%  3.2 MB/s  2 min 10 s left" for a byte transfer, or "45%  owner/name" for a git refresh. */
    private static String statusText(@NonNull Context ctx, @NonNull ContentUpdater u, int pct) {
        StringBuilder sb = new StringBuilder();
        sb.append(pct).append('%');
        if (u.supportsSpeedEta()) {
            long spd = u.speed();
            if (spd > 0) sb.append("  ·  ").append(ByteFormatter.toHuman(spd)).append("/s");
            long eta = u.etaSeconds();
            if (eta >= 0) sb.append("  ·  ").append(etaText(ctx, eta));
        } else {
            String detail = u.currentDetail();
            if (detail != null && !detail.isEmpty()) sb.append("  ·  ").append(detail);
        }
        return sb.toString();
    }

    /** Whole-job seconds remaining as a localized "N min M s left" / "M s left". */
    private static String etaText(@NonNull Context ctx, long seconds) {
        if (seconds < 0) return "";
        long m = seconds / 60, s = seconds % 60;
        return m > 0 ? ctx.getString(R.string.k2go_eta_min_sec, (int) m, (int) s)
                     : ctx.getString(R.string.k2go_eta_sec, (int) s);
    }

    private static void terminal(@NonNull ContentUpdater u, @NonNull View trigger, @NonNull ViewGroup parent,
                                 @NonNull View progress, @NonNull View.OnAttachStateChangeListener detach, int msgRes) {
        u.setListener(null);
        trigger.removeOnAttachStateChangeListener(detach);
        if (progress.getParent() == parent) parent.removeView(progress);
        trigger.setEnabled(true);
        if (trigger.isAttachedToWindow()) {
            Snackbars.make(trigger, trigger.getContext().getString(msgRes)).show();
        }
        u.finishSession();
    }
}
