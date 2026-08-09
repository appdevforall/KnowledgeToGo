/*
 * ============================================================================
 * Name        : ContentStateInvalidator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Carries out SystemReplacement against the app's content stores,
 *               in two moments (ADFA-5070).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.iiab.controller.kolibri.data.KolibriWishlist;
import org.iiab.controller.kolibri.presentation.KolibriSeedRepository;
import org.iiab.controller.kolibri.presentation.KolibriSeedService;
import org.iiab.controller.redesign.BooksDownloadService;
import org.iiab.controller.redesign.BooksWishlist;
import org.iiab.controller.redesign.MapsWishlist;
import org.iiab.controller.redesign.ZimDownloadService;
import org.iiab.controller.redesign.ZimWishlist;
import org.iiab.controller.system.domain.SystemReplacement;

/**
 * The one place that forgets content state when the system is replaced.
 *
 * <p>{@link SystemReplacement} decides <em>what</em>; this carries it out against
 * the seven stores that actually hold it — three in-memory download sessions (ZIM,
 * Books, Kolibri) and four on-disk orders (those three plus Maps). Maps has no
 * session of its own: it keeps no download progress in the app, only a wishlist.
 *
 * <p><b>Two moments, because they have different costs.</b>
 *
 * <ul>
 *   <li>{@link #replacementStarting} runs before the destruction. It only touches
 *       the sessions, which describe work that is about to stop meaning anything —
 *       clearing them early loses nothing even if the operation then fails.</li>
 *   <li>{@link #replacementSucceeded} runs once the system really has been
 *       replaced, and is what discards the pending orders. Doing that up front was
 *       the earlier mistake: a restore whose tar will not open, or a clone whose
 *       transfer dies on the first byte, leaves the system untouched — and the
 *       user's orders would already have been thrown away for an operation that
 *       never happened.</li>
 * </ul>
 *
 * <p><b>Every destructive route calls both</b>, not just the install service. Five
 * routes replace or remove a system and only two go through {@code InstallService}:
 * a reinstall and a reset. Restore extracts a tar over the rootfs from
 * {@code DeepOpService}, clone-receive rsyncs over it from {@code CloneFragment},
 * and the legacy delete removes it outright.
 *
 * <p>Touches SharedPreferences, so call it off the main thread.
 *
 * <p>Deliberately noisy in the log. Discarding a user's pending order is not a
 * detail, and there is nowhere yet to record it durably, so the log line is the
 * only audit there is.
 */
public final class ContentStateInvalidator {

    private static final String TAG = "K2Go-SystemState";

    private ContentStateInvalidator() {
    }

    /**
     * The system is about to be replaced: stop and forget the downloads.
     *
     * <p>A session is not just bookkeeping — there may be a foreground service still
     * running behind it. Clearing the record alone would leave that service alive
     * with nothing describing it, so each stream is asked to cancel first and the
     * record is cleared after. Cancelling something that is not running is a no-op,
     * which is cheaper than each caller working out whether it needs to.
     */
    public static void replacementStarting(Context ctx, SystemReplacement.Cause cause) {
        if (ctx == null || cause == null || !SystemReplacement.clearsSessions(cause)) {
            return;
        }
        Context app = ctx.getApplicationContext();
        int cancelled = 0;

        // Only a stream that is actually running is asked to stop, and that is not a
        // micro-optimisation: every ACTION_CANCEL branch answers with stopForeground
        // + stopSelf without ever calling startForeground, so starting a service that
        // was not running would leave Android waiting for a foreground notification
        // that never comes — ForegroundServiceDidNotStartInTimeException, on the
        // ordinary path where nothing was downloading at all.
        if (ZimDownloadService.isRunning()) {
            cancelled += cancel(app, ZimDownloadService.class, ZimDownloadService.ACTION_CANCEL);
        }
        if (BooksDownloadService.isRunning()) {
            cancelled += cancel(app, BooksDownloadService.class, BooksDownloadService.ACTION_CANCEL);
        }
        if (KolibriSeedRepository.get().isRunning()) {
            cancelled += cancel(app, KolibriSeedService.class, KolibriSeedService.ACTION_CANCEL);
        }

        ZimDownloadService.finishSession();
        BooksDownloadService.finishSession();
        KolibriSeedService.finishSession();

        Log.i(TAG, "content sessions cleared before " + cause
                + (cancelled > 0 ? " — " + cancelled + " download(s) in flight were cancelled" : ""));
    }

    /**
     * The system really was replaced: discard the orders that were placed against
     * the old one.
     *
     * <p>No-op for {@link SystemReplacement.Cause#REINSTALL}: the wizard clears the
     * wishlists when it opens and the user refills them on the way in, so those
     * orders belong to the system that was just created.
     */
    public static void replacementSucceeded(Context ctx, SystemReplacement.Cause cause) {
        if (ctx == null || cause == null || !SystemReplacement.clearsOrders(cause)) {
            return;
        }
        Context app = ctx.getApplicationContext();
        int discarded = pendingOrders(app);

        ZimWishlist.clear(app);
        BooksWishlist.clear(app);
        KolibriWishlist.clear(app);
        // ADFA-5070: Maps was missing from the wizard's own clearing too. The other
        // three were added one at a time and this one never followed.
        MapsWishlist.clear(app);

        Log.i(TAG, "after " + cause + ": " + discarded + " pending order(s) discarded");
    }

    /**
     * Asks a running stream to stop.
     *
     * @return 1 when the stop was delivered, 0 when it could not be — the session is
     *         cleared either way, because the point is that it no longer describes
     *         anything real
     */
    private static int cancel(Context app, Class<?> service, String action) {
        try {
            ContextCompat.startForegroundService(app,
                    new Intent(app, service).setAction(action));
            return 1;
        } catch (Exception e) {
            Log.w(TAG, "could not cancel " + service.getSimpleName() + ": " + e.getMessage());
            return 0;
        }
    }

    /** How many orders are about to be thrown away, for the log line. */
    private static int pendingOrders(Context app) {
        int n = 0;
        try {
            n += ZimWishlist.size(app);
            n += BooksWishlist.size(app);
            n += KolibriWishlist.size(app);
            n += MapsWishlist.has(app) ? 1 : 0;
        } catch (Exception e) {
            // Counting is for the log only; never let it stop the invalidation.
            Log.w(TAG, "could not count pending orders: " + e.getMessage());
        }
        return n;
    }
}
