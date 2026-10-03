/*
 * ============================================================================
 * Name        : ForgejoRepoRefresh.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-422 / K2GO-443. The single "Update repos" flow, shared by the module detail button
 *               and the module action sheet row. It gates like the dashboard update (needs internet, then
 *               metered consent), then drives the durable job engine (type "forgejo") through the shared
 *               RestContentClient: a determinate bar (repo N of M) with the current repo name and a Cancel,
 *               injected right after the trigger view.
 *
 *               Forgejo is a git operation (fetch + fast-forward/merge + authenticated push per seeded
 *               example repo), not a file download, so there is no speed and NO pause/resume: the progress
 *               is repo-count, and a retry re-runs the idempotent refresh (the engine owns that).
 *
 *               Lifecycle: no persistent app-side state here. The box job is durable and runs on, so a host
 *               that goes away just drops the UI updates (guarded by View.isAttachedToWindow()); the client
 *               polls to a terminal state and tears itself down. A terminal state (done / failed /
 *               cancelled) removes the inline UI and reports it in a snackbar.
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.presentation;

import android.app.Activity;
import android.content.Context;
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
import org.appdevforall.k2go.content.RestContentClient;
import org.appdevforall.k2go.util.Snackbars;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ForgejoRepoRefresh {

    private ForgejoRepoRefresh() {}

    // The box runner reads the repo set itself and ignores the job items, so a single sentinel satisfies
    // POST /forgejo/download (which requires a non-empty items/ids) and keys the start-or-attach / guard.
    private static final String SENTINEL = "repos";

    /**
     * Gate (internet, then metered consent) then run the refresh with progress injected right after
     * {@code trigger}. The trigger stays in place (only disabled) as a visible anchor for the snackbar.
     */
    public static void start(@NonNull Activity act, @NonNull View trigger) {
        if (!org.appdevforall.k2go.networkpolicy.data.AndroidNetworkClassifier.hasInternet(act)) {
            Snackbars.make(trigger, act.getString(R.string.k2go_dash_needs_internet)).show();
            return;
        }
        // Same metered gate as the dashboard update: the box git-fetches over the device network.
        org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate.guardHeavyStart(act, () -> run(trigger));
    }

    private static void run(@NonNull View trigger) {
        final ViewGroup parent = (ViewGroup) trigger.getParent();
        if (parent == null || !trigger.isAttachedToWindow()) return;   // host went away during the gate
        final Context ctx = trigger.getContext();
        final float d = ctx.getResources().getDisplayMetrics().density;
        final int side = Math.round(20 * d);

        final LinearLayout progress = new LinearLayout(ctx);
        progress.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = side; plp.rightMargin = side; plp.topMargin = Math.round(8 * d);
        progress.setLayoutParams(plp);

        final TextView label = new TextView(ctx);
        label.setText(R.string.k2go_forgejo_updating);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        label.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        progress.addView(label);

        // The current repo being refreshed (from the job's detail), advancing one at a time.
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
        bar.setIndeterminate(true);   // becomes determinate once the first per-repo percent arrives
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

        final RestContentClient client = new RestContentClient("forgejo");
        final boolean[] settled = { false };   // one terminal cleanup (done / error / cancel)
        // Per-repo outcome tally the runner carries in the final detail (K2GO_SUMMARY changed problems
        // total); -1 = unknown (an older box). Kept so "done" can still say "some blocked" / "up to date".
        final int[] summary = { -1, -1, -1 };

        cancel.setOnClickListener(cv -> {
            cancel.setEnabled(false);
            label.setText(R.string.k2go_forgejo_update_cancelling);
            client.cancel();   // cancel() tears down without a listener callback, so settle here
            terminal(settled, trigger, parent, progress, R.string.k2go_forgejo_update_cancelled);
        });

        client.start(sentinelBody(), new RestContentClient.Listener() {
            @Override public void onProgress(int percent, String speed) {
                if (!liveLine.isAttachedToWindow()) return;
                bar.setIndeterminate(percent < 0);
                if (percent >= 0) bar.setProgressCompat(percent, true);
            }
            @Override public void onIndexing() {
                if (liveLine.isAttachedToWindow()) bar.setIndeterminate(true);
            }
            @Override public void onLog(String line) {
                final String t = line.trim();
                if (t.startsWith("K2GO_SUMMARY")) {   // app<->runner token: the final outcome tally, not a repo
                    String[] p = t.split("\\s+");
                    if (p.length >= 4) {
                        try {
                            summary[0] = Integer.parseInt(p[1]);
                            summary[1] = Integer.parseInt(p[2]);
                            summary[2] = Integer.parseInt(p[3]);
                        } catch (NumberFormatException ignore) { /* leave unknown */ }
                    }
                    return;
                }
                // Otherwise the job detail is the current repo ("owner/name"); drop the org prefix.
                final String shown = t.replace("AppDevForAll/", "");
                if (liveLine.isAttachedToWindow()) liveLine.setText(shown);
            }
            @Override public void onDone() {
                terminal(settled, trigger, parent, progress, messageFor(summary[0], summary[1], summary[2]));
            }
            @Override public void onError(String message) {
                terminal(settled, trigger, parent, progress, R.string.k2go_forgejo_update_failed);
            }
        });
    }

    private static JSONObject sentinelBody() {
        try { return new JSONObject().put("ids", new JSONArray().put(SENTINEL)); }
        catch (Exception e) { return new JSONObject(); }
    }

    /**
     * Map the per-repo outcome tally to a user message. The job finished (this is onDone), so the only
     * question is what happened per repo. -1 counts = unknown (an older box without the summary) -> the
     * generic "updated". changed = repos that advanced; problems = conflict or a fetch/push failure.
     */
    private static int messageFor(int changed, int problems, int total) {
        if (problems > 0) {
            // some repos could not be updated (reconcile in the web UI); all vs some depends on the rest
            return (total - problems > 0) ? R.string.k2go_forgejo_update_some_failed
                                          : R.string.k2go_forgejo_update_all_failed;
        }
        if (changed > 0) return R.string.k2go_forgejo_update_done;    // at least one repo advanced
        if (changed == 0 && total >= 0) return R.string.k2go_forgejo_update_none;   // nothing to update
        return R.string.k2go_forgejo_update_done;                     // unknown counts (older box)
    }

    /** Remove the inline UI, re-enable the trigger, and report the outcome once (guarded). */
    private static void terminal(boolean[] settled, @NonNull View trigger, @NonNull ViewGroup parent,
                                 @NonNull View progress, int msgRes) {
        if (settled[0]) return;
        settled[0] = true;
        if (progress.getParent() == parent) parent.removeView(progress);
        trigger.setEnabled(true);
        if (trigger.isAttachedToWindow()) {
            Snackbars.make(trigger, trigger.getContext().getString(msgRes)).show();
        }
    }
}
