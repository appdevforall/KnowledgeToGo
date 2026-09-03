/*
 * ============================================================================
 * Name        : DashboardCardStatus.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5339. The one place that FETCHES the Dashboard card's update state — cache-first,
 *               then a live /update-check, degrading to offline — and delivers it as a resolved
 *               DashboardCardState. Both surfaces (the detail card and the module-hub row) call this
 *               and only render; without it each would re-implement the cache/live/offline dance and
 *               they would drift. The rule that turns inputs into a state is the pure
 *               DashboardCardState (domain); this is the glue that gathers the inputs.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;

import org.appdevforall.k2go.dashboard.domain.DashboardCardState;

public final class DashboardCardStatus {

    private DashboardCardStatus() {}

    /** Delivered on the main thread. May be called TWICE: once immediately with the cached/offline
     *  state (so the pill isn't stuck on "Checking…"), then again with the live result. The caller
     *  guards its own view lifecycle (isAdded) before painting. */
    public interface Listener { void onState(DashboardCardState state); }

    /**
     * Resolve the card state and hand it to {@code l}. Cache and connectivity are read synchronously
     * on the calling (main) thread; the live check is async via {@link DashboardClient#updateCheck},
     * which already posts back to main.
     */
    public static void fetch(Context context, Listener l) {
        final Context ctx = context.getApplicationContext();
        final boolean online = DashboardRebuild.hasInternet(ctx);

        // Cached-first (ADFA-5026) so the UI isn't blank while the live check runs; the cache holds
        // only the boolean, so no versions -> no arrow from a cached state.
        if (UpdateStatusCache.has(ctx)) {
            l.onState(DashboardCardState.resolve(
                    online, false, false, null, null, true, UpdateStatusCache.updateAvailable(ctx)));
        } else if (!online) {
            l.onState(DashboardCardState.resolve(false, false, false, null, null, false, false));
        }
        if (!online) return;   // offline: no live check; the state above stands

        DashboardClient.updateCheck(new DashboardClient.UpdateCb() {
            @Override public void onResult(String installed, String available, boolean updateAvailable) {
                UpdateStatusCache.save(ctx, updateAvailable);
                l.onState(DashboardCardState.resolve(
                        true, true, updateAvailable, installed, available, true, updateAvailable));
            }
            @Override public void onErr(String message) {
                // Online but the check failed (box stopped): fall back to the cached state, or Checking
                // when nothing is cached. Re-read connectivity in case the network just dropped.
                l.onState(DashboardCardState.resolve(
                        DashboardRebuild.hasInternet(ctx), false, false, null, null,
                        UpdateStatusCache.has(ctx), UpdateStatusCache.updateAvailable(ctx)));
            }
        });
    }
}
