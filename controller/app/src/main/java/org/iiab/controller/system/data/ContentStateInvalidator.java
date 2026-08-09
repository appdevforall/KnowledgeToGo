/*
 * ============================================================================
 * Name        : ContentStateInvalidator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Carries out SystemReplacement against the app's content stores
 *               (ADFA-5070).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.kolibri.data.KolibriWishlist;
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
 * <p><b>Every destructive route calls this, not just the install service.</b> Five
 * routes replace or remove a system and only two of them go through
 * {@code InstallService}: a reinstall and a reset. Restore extracts a tar over the
 * rootfs from {@code DeepOpService}, clone-receive rsyncs over it from
 * {@code CloneFragment}, and the legacy delete removes it outright. Hooking the
 * service alone would have covered two cases out of five and looked finished.
 *
 * <p>Deliberately noisy in the log. Discarding a user's pending order is not a
 * detail, and there is nowhere yet to record it durably, so the log line is the
 * only audit there is: it names the cause and says how much was thrown away.
 */
public final class ContentStateInvalidator {

    private static final String TAG = "K2Go-SystemState";

    private ContentStateInvalidator() {
    }

    /**
     * Forgets what the previous system was about.
     *
     * <p>Safe to call more than once and safe when there is nothing to forget —
     * every route may fire it, including ones that turn out to be no-ops, which is
     * cheaper than each caller deciding whether it is worth it.
     *
     * @param ctx   any context; the application context is used
     * @param cause which route is replacing the system
     */
    public static void systemReplaced(Context ctx, SystemReplacement.Cause cause) {
        if (ctx == null || cause == null) {
            return;
        }
        Context app = ctx.getApplicationContext();

        if (SystemReplacement.clearsSessions(cause)) {
            // In-memory, so this only matters while the process outlives the wipe —
            // which is exactly the case that produced a finished download reported
            // against an empty content database.
            ZimDownloadService.finishSession();
            BooksDownloadService.finishSession();
            KolibriSeedService.finishSession();
        }

        if (!SystemReplacement.clearsOrders(cause)) {
            Log.i(TAG, "content state invalidated (" + cause + "): sessions cleared,"
                    + " orders kept — the wizard is about to place them");
            return;
        }

        int discarded = pendingOrders(app);
        ZimWishlist.clear(app);
        BooksWishlist.clear(app);
        KolibriWishlist.clear(app);
        // ADFA-5070: Maps was missing from the wizard's own clearing too. The other
        // three were added one at a time and this one never followed.
        MapsWishlist.clear(app);

        Log.i(TAG, "content state invalidated (" + cause + "): sessions cleared, "
                + discarded + " pending order(s) discarded");
    }

    /** How many orders are about to be thrown away, for the log line. */
    private static int pendingOrders(Context app) {
        int n = 0;
        try {
            n += ZimWishlist.all(app).length();
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
