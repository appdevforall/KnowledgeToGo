/*
 * ============================================================================
 * Name        : KolibriProvisioner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. Post-install drain of the Kolibri wishlist. Once the
 *               system is installed and the server is up, hands the banked order
 *               to KolibriSeedService. Kolibri is a LIVE operation (server up),
 *               so this must only run when the server is alive. Idempotent: the
 *               wishlist is cleared once handed off.
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.presentation;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.kolibri.data.KolibriWishlist;
import org.appdevforall.k2go.redesign.MapsProvisioner;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the banked order into a running session, when — and only when — it is
 * safe to do so.
 *
 * <p>Mirrors {@code ZimProvisioner} and {@code BooksProvisioner}, with one
 * deferral they did not previously have between them.
 */
public final class KolibriProvisioner {

    private KolibriProvisioner() {
    }

    private static final String TAG = "K2Go-Provision";

    /** True when there is a banked Kolibri order waiting. */
    public static boolean hasPending(Context ctx) {
        return KolibriWishlist.size(ctx) > 0;
    }

    /**
     * ADFA-4954: whether a drain would proceed <em>right now</em>, without writing
     * anything.
     *
     * <p>Now only {@link #drain} asks. It used to be public API for the live door, which
     * checked before writing so it could refuse with "Another download is running" rather
     * than bank an order that nothing would pick up — true at the time, because the courses
     * wishlist was drained only by the progress index. ADFA-5074 made the Home pump courses
     * too and gave every door the index as its landing, so a deferred order is both drained
     * and visible ("Queued" on its row). The door banks and lets this defer; a queue is a
     * better answer than a refusal, and the honest one now that something drains it.
     *
     * <p>The rule stays here rather than being restated at the call site — that is
     * how the four {@code *Wizard} booleans went wrong.
     */
    static boolean canDrainNow(Context ctx) {
        if (org.appdevforall.k2go.install.presentation.ModuleQueueRepository.get().isRunning()
                || MapsProvisioner.hasPending(ctx)
                || org.appdevforall.k2go.redesign.DashboardRebuildService.isRunning()) {   // ADFA-5333
            Log.d(TAG, "kolibri drain blocked: proot (runrole) or dashboard-update work is in flight");
            return false;
        }
        // ADFA-5074: unfinished work only. This read a merely registered session, so a
        // download that had already completed and was waiting to be dismissed refused the
        // next one with "something else is downloading" while nothing was — and the only
        // way out was force-stopping the app, because these are process statics.
        // Serialising exists because each stream measures free space at its own moment;
        // one that has finished has already been absorbed by the disk.
        if (org.appdevforall.k2go.system.data.PendingContent.anyUnfinished(ctx)) {
            Log.d(TAG, "kolibri drain blocked: a content stream still has work to do");
            return false;
        }
        return true;
    }

    /**
     * Hands the wishlist to {@link KolibriSeedService} and clears it.
     *
     * <p>ADFA-5074: this javadoc lived above {@code canDrainNow}, together with a second,
     * orphaned block describing a return value. Both are folded here, where the method is.
     *
     * <p>Two deferrals, for different reasons:
     * <ul>
     *   <li><b>proot</b> (ADFA-4900) — Ansible forks background processes and
     *       concurrent REST work risks corruption, so no REST stream may run
     *       while a runrole is pending or in flight.</li>
     *   <li><b>the other live REST streams</b> (ADR-4954 D8) — each of the three
     *       measures free space independently and at a different moment, so all
     *       of them can pass their own check and jointly fill the disk. A Kolibri
     *       channel runs to tens of GB, which makes it the one most likely to be
     *       the straw. ADFA-5074: the rule is now one call for all three, and it
     *       asks about <em>unfinished</em> work — a stream that has finished has
     *       already been absorbed by the disk and protects nothing.</li>
     * </ul>
     *
     * <p>No deadlock is possible: deferring means returning so a later pass can retry, and
     * both pumps — {@code SetupProgressActivity.orchestrateStep()} and the Home's fallback in
     * {@code LibraryHomeFragment} — call the provisioners in a fixed order, so the first one
     * starts and the rest wait.
     *
     * @return true when the order was handed to {@code KolibriSeedService}; false when it was
     *         deferred or there was nothing to do. Deferred is not a failure: the order stays
     *         in the wishlist and the next pass picks it up. Callers that once treated false
     *         as "tell the user no" no longer exist — that is what the queue replaced.
     */
    public static boolean drain(Context ctx) {
        if (!canDrainNow(ctx)) {
            return false;
        }
        if (KolibriWishlist.size(ctx) == 0) {
            return false;
        }

        final Context app = ctx.getApplicationContext();
        JSONArray order = KolibriWishlist.all(app);
        Log.i(TAG, "kolibri drain: " + order.length() + " in wishlist");

        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Long> bytes = new ArrayList<>();
        List<String> nodes = new ArrayList<>();

        for (int i = 0; i < order.length(); i++) {
            JSONObject o = order.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("channelId", "");
            if (id.isEmpty()) {
                continue;
            }
            ids.add(id);
            // The name is banked with the order rather than looked up now:
            // Kolibri's task API requires channel_name, and the catalog the pick
            // came from can have moved on by the time we drain.
            String name = o.optString("name", "");
            labels.add(name.isEmpty() ? id : name);
            bytes.add(o.optLong("bytes", 0L));
            nodes.add(joinNodes(o.optJSONArray("nodeIds")));
        }

        if (ids.isEmpty()) {
            Log.w(TAG, "kolibri drain: nothing resolved from wishlist");
            KolibriWishlist.clear(app);
            return false;
        }

        long[] b = new long[bytes.size()];
        for (int i = 0; i < b.length; i++) {
            b[i] = bytes.get(i);
        }

        Log.i(TAG, "kolibri drain: handing " + ids.size() + " to KolibriSeedService");
        KolibriSeedService.start(app,
                ids.toArray(new String[0]),
                labels.toArray(new String[0]),
                b,
                nodes.toArray(new String[0]));

        // TODO(ADFA-4874): clearing here means that if the process dies mid-download
        // both the wishlist and the session are lost (orphaned partial). The durable
        // background-jobs monitor should own this hand-off so it survives process death.
        KolibriWishlist.clear(app);
        return true;
    }

    /** Node ids as a comma-joined string; empty means the whole channel. */
    private static String joinNodes(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String n = arr.optString(i, "");
            if (!n.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(n);
            }
        }
        return sb.toString();
    }
}
