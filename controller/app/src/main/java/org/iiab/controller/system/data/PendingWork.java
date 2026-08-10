/*
 * ============================================================================
 * Name        : PendingWork.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One door for forgetting everything arranged against a system
 *               that is gone (ADFA-5074).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;

/**
 * Discards every order the user placed against a system that no longer exists.
 *
 * <p><b>Why one more class rather than two calls.</b> There are two families of pending
 * work — content orders behind {@link PendingContent}, and the module-install flow's own two
 * stores behind {@code ModuleInstallState} — and for a while both call sites listed them in
 * a pair:
 *
 * <pre>
 *     PendingContent.clearAll(ctx);
 *     ModuleInstallState.clearAll(ctx);
 * </pre>
 *
 * <p>That is the same mistake as before, one level up. It began as six stores enumerated by
 * hand in five screens; consolidating them into two facades left three places enumerating
 * the two facades. A seventh store tomorrow would need adding in all of them, and the one
 * that got missed would be the one nobody tested — which is precisely how a stale module
 * batch ended up drawing a row for an install that never happened.
 *
 * <p>So the list lives here, and both moments ask the same question.
 *
 * <p><b>Two moments, one reason.</b> A fresh wizard run drops whatever an abandoned earlier
 * run left behind, and a completed system replacement drops the orders placed against the box
 * that was wiped. They arrive by different routes but they are the same event: the work was
 * arranged against a system that is no longer there.
 *
 * <p>Touches SharedPreferences, so prefer calling it off the main thread. The wizard path
 * calls it during {@code onCreate} because that is where the decision is made; the writes are
 * {@code apply()} and asynchronous.
 */
public final class PendingWork {

    private PendingWork() {
    }

    /** Everything: content orders, and the module flow's scheduled list and batch. */
    public static void clearAll(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        PendingContent.clearAll(app);
        org.iiab.controller.redesign.ModuleInstallState.clearAll(app);
    }
}
