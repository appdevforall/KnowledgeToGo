/*
 * ============================================================================
 * Name        : ModuleInstallState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The module-install flow's two stores, discarded together
 *               (ADFA-5074).
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;

/**
 * Everything the module-install flow remembers between screens, in one place to forget.
 *
 * <p><b>Why this exists.</b> The flow keeps two stores and each was only cleared at the
 * moment it happened to be finished with: {@link ModuleWishlist} when the batch is handed
 * to the engine, {@link ModuleBatch} when the install index reaches the library. Neither is
 * cleared when the <em>system</em> goes away, so both outlived a wipe:
 *
 * <ul>
 *   <li>a stale {@link ModuleBatch} made the install index draw a row for a module from a
 *       previous, unrelated install — "Books reader" appearing in a run that never asked for
 *       it. Worse than cosmetic: {@code SetupProgressActivity.moduleInSession()} latches on
 *       it, and that decides whether the index becomes a gate, whether "Run in background"
 *       is offered, and whether the server restart is awaited;</li>
 *   <li>a stale {@link ModuleWishlist} is a scheduled install that survived the box it was
 *       scheduled against. At best it repeats work the new tier already shipped; at worst it
 *       runs a runrole nobody asked for in this run.</li>
 * </ul>
 *
 * <p>The content side already had this — four wishlists behind {@code PendingContent} — and
 * these two were simply not in that enumeration, because a module install is not content.
 * Rather than teach two more call sites to list them, they are cleared together here, and
 * both facades sit behind {@code PendingWork} so no caller enumerates anything.
 *
 * <p><b>Not {@code HiddenModules}</b>, which also persists module keys. That one records which
 * modules the user chose not to see on Home — a preference about the app, not work arranged
 * against a system — so it is meant to outlive a wipe. Named here because it looks like a
 * third store to anyone grepping for module keys, and the next person should not have to work
 * out why it was left alone.
 */
public final class ModuleInstallState {

    private static final String TAG = "K2Go-ModuleState";

    private ModuleInstallState() {
    }

    /** Whether either persisted store still holds something. */
    public static boolean any(Context ctx) {
        return ctx != null && (ModuleWishlist.has(ctx) || ModuleBatch.has(ctx));
    }

    /**
     * Forgets both: what was scheduled, and what a previous run was installing.
     *
     * <p>Called where the content wishlists are dropped — a fresh wizard run, and a completed
     * system replacement — because it is the same event for the same reason: the work was
     * arranged against a system that no longer exists.
     */
    public static void clearAll(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        // Unconditional: asking first cost two SharedPreferences reads purely to decide
        // whether to log, and one caller is the wizard's onCreate — main-thread disk for a
        // message. Both clears are idempotent, so there is nothing to guard.
        ModuleWishlist.clear(app);
        ModuleBatch.clear(app);

        // ADFA-5074: and the queue's own verdict, which is held in memory for the life of the
        // process. A finished install leaves it on DONE, and the install index reads that phase
        // to decide whether a row is complete — so a new run drew a green check over a maps
        // install that had not started, and only corrected itself when the real runrole
        // published RUNNING. The row was trusting a verdict without asking whose it was.
        //
        // Never over a live one: a runrole in flight owns this state, and posting idle under it
        // would replace a true report with a false one. On a replacement the queue has already
        // been stopped; on a fresh wizard run there is nothing to stop.
        ModuleQueueRepository queue = ModuleQueueRepository.get();
        if (!queue.isRunning()) {
            queue.postIdle();
        }
        Log.i(TAG, "module install state discarded (scheduled list, batch, queue verdict)");
    }
}
