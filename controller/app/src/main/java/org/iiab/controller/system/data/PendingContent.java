/*
 * ============================================================================
 * Name        : PendingContent.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The one place that knows every kind of content a run can be
 *               carrying (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.kolibri.data.KolibriWishlist;
import org.iiab.controller.kolibri.presentation.KolibriProvisioner;
import org.iiab.controller.kolibri.presentation.KolibriSeedRepository;
import org.iiab.controller.redesign.BooksDownloadService;
import org.iiab.controller.redesign.BooksProvisioner;
import org.iiab.controller.redesign.BooksWishlist;
import org.iiab.controller.redesign.MapsProvisioner;
import org.iiab.controller.redesign.MapsWishlist;
import org.iiab.controller.redesign.ZimDownloadService;
import org.iiab.controller.redesign.ZimProvisioner;
import org.iiab.controller.redesign.ZimWishlist;

/**
 * Answers "is this run carrying content, and of what sort?" for every content type
 * at once.
 *
 * <p><b>Why this exists.</b> Four content types were added one at a time — ZIM,
 * Books, Maps, then Courses — and each arrival had to be registered by hand in every
 * screen that asks whether there is anything to drain. Nobody found them all. A
 * survey for ADFA-4954 turned up five places listing the types, with three different
 * subsets:
 *
 * <ul>
 *   <li>{@code LibraryActivity} decided whether to open "Finishing setup" from
 *       Books + ZIM + Maps. Courses were missing, so a wizard run that chose only
 *       courses never opened that screen, {@code KolibriProvisioner} was never
 *       called, and the content the user picked was simply never downloaded.</li>
 *   <li>{@code SetupProgressActivity}'s "no REST content in this run" test read
 *       ZIM + Books only, so a run mixing courses with a proot module batch could
 *       declare itself complete while the courses were still downloading.</li>
 *   <li>{@code SetupLibraryActivity} cleared Books + ZIM + Courses when a fresh
 *       wizard run starts, but not Maps — leaving an aborted run's map selection to
 *       be drained later against a system it was not chosen for.</li>
 * </ul>
 *
 * <p>Each of those was a one-line omission, and each was invisible until someone
 * exercised that exact combination. The fix is not three more lines: it is that the
 * list lives once, so a fifth content type is registered here and every caller
 * follows.
 *
 * <p><b>What is deliberately not merged.</b> There are two different questions here
 * and they must not collapse into one:
 *
 * <ul>
 *   <li>{@link #anyBanked} — is there an <em>order</em> waiting, of any kind. Maps
 *       counts: it is content the user chose.</li>
 *   <li>{@link #anyLive} — is there <b>live (REST)</b> content in this run, either
 *       running or banked. Maps does <em>not</em> count here, because it installs
 *       with the system stopped (runrole under proot) and its completion is tracked
 *       by the module queue, not by a download stream.</li>
 * </ul>
 *
 * <p>A caller that asks the wrong one gets a wrong answer that looks plausible, so
 * they are named after what they are for rather than after the lists they read.
 */
public final class PendingContent {

    private static final String TAG = "K2Go-PendingContent";

    private PendingContent() {
    }

    /**
     * Whether any content order is waiting to be provisioned — live or stopped.
     *
     * <p>The question "should the post-install screen open at all?".
     */
    public static boolean anyBanked(Context ctx) {
        if (ctx == null) {
            return false;
        }
        Context app = ctx.getApplicationContext();
        return ZimProvisioner.hasPending(app)
                || BooksProvisioner.hasPending(app)
                || KolibriProvisioner.hasPending(app)
                || MapsProvisioner.hasPending(app);
    }

    /**
     * Whether this run carries <b>live</b> content: a REST stream that is running, or
     * an order for one that has not been handed over yet.
     *
     * <p>Maps is excluded on purpose — see the class note. The question "can this run
     * finish on the proot queue alone?" is the negation of this one.
     */
    public static boolean anyLive(Context ctx) {
        // Excluding nothing. Kept as one implementation so the list of live types is
        // written once even here — two copies inside the same class would be the same
        // mistake at a smaller scale.
        return anyLiveOtherThan(ctx, null);
    }

    /**
     * The same question as {@link #anyLive}, ignoring one stream.
     *
     * <p>For "is this the only live thing happening?", asked by a screen that has just
     * started a stream and wants to know whether to open its detail or stay on the
     * index. The stream that was just confirmed may not have registered its session
     * yet, so it is excluded by name rather than by looking.
     *
     * @param key one of {@code "zim"}, {@code "books"}, {@code "kolibri"} — the same
     *            keys the progress rows use. An unknown key excludes nothing, which
     *            keeps the caller on the index: the safe side of this question.
     */
    public static boolean anyLiveOtherThan(Context ctx, String key) {
        if (ctx == null) {
            return false;
        }
        Context app = ctx.getApplicationContext();
        boolean zim = !"zim".equals(key)
                && (ZimDownloadService.hasSession() || ZimProvisioner.hasPending(app));
        boolean books = !"books".equals(key)
                && (BooksDownloadService.hasSession() || BooksProvisioner.hasPending(app));
        boolean kolibri = !"kolibri".equals(key)
                && (KolibriSeedRepository.get().hasSession() || KolibriProvisioner.hasPending(app));
        return zim || books || kolibri;
    }

    /**
     * How many orders are banked. Maps has no per-item count — it is one selection of
     * several layers — so it contributes 1.
     *
     * <p>For logging and for "N items waiting" style copy; never for a decision, which
     * should use {@link #anyBanked}.
     */
    public static int bankedCount(Context ctx) {
        if (ctx == null) {
            return 0;
        }
        Context app = ctx.getApplicationContext();
        int n = 0;
        try {
            n += ZimWishlist.size(app);
            n += BooksWishlist.size(app);
            n += KolibriWishlist.size(app);
            n += MapsWishlist.has(app) ? 1 : 0;
        } catch (Exception e) {
            // Counting is never the point of the call; do not let it stop the caller.
            Log.w(TAG, "could not count banked orders: " + e.getMessage());
        }
        return n;
    }

    /**
     * Discards every banked order, of every type.
     *
     * <p>Two callers, for the same reason in two moments: a fresh wizard run drops
     * whatever an abandoned earlier run left behind, and a completed system
     * replacement drops the orders that were placed against the system that is gone.
     * Both used to enumerate the types themselves, and both were missing one.
     */
    public static void clearAll(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        ZimWishlist.clear(app);
        BooksWishlist.clear(app);
        KolibriWishlist.clear(app);
        MapsWishlist.clear(app);
    }
}
