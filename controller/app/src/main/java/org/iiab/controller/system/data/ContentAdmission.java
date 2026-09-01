/*
 * ============================================================================
 * Name        : ContentAdmission.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The one answer to "may a REST content stream start right now?". Books, ZIM and
 *               Kolibri each carried an identical copy of this rule; a fourth stream would have
 *               carried a fourth. Behaviour is unchanged — the checks, their order and the reasons
 *               are the ones the three copies already applied.
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.install.presentation.ModuleQueueRepository;
import org.iiab.controller.redesign.DashboardRebuildService;
import org.iiab.controller.redesign.MapsProvisioner;

/**
 * Admission for the live REST content streams (Books, ZIM, Kolibri channels).
 *
 * <p>The rule lives here rather than being restated at each call site — that is how the four
 * {@code *Wizard} booleans went wrong. It answers for the streams that the box downloads over REST;
 * the proot operations (a module runrole, maps) have their own, different gate, and deliberately do
 * not share this one: they are what this rule defers <em>to</em>.
 *
 * <p>Two deferrals, for different reasons:
 * <ul>
 *   <li><b>proot</b> (ADFA-4900) — Ansible forks background processes and concurrent REST work
 *       risks corruption, so no REST stream may run while a runrole is pending or in flight.
 *       ADFA-5333 adds the live dashboard update, which restarts dash-node underneath a stream.</li>
 *   <li><b>the other live REST streams</b> (ADR-4954 D8) — each measures free space independently
 *       and at a different moment, so all of them can pass their own check and jointly fill the
 *       disk. A Kolibri channel runs to tens of GB, which makes it the one most likely to be the
 *       straw.</li>
 * </ul>
 *
 * <p>ADFA-5074: the second check asks about <em>unfinished</em> work, not about a registered
 * session. Reading a merely registered session refused the next download while nothing was running
 * — a stream that had already completed and was waiting to be dismissed still said no, and the only
 * way out was force-stopping the app, because these are process statics. A finished stream has
 * already been absorbed by the disk. It also means a fourth content type is one line in
 * {@code ContentType} instead of an edit here.
 *
 * <p>Deferred is not a failure: callers leave the order banked and the next pass picks it up.
 */
public final class ContentAdmission {

    private static final String TAG = "K2Go-Provision";

    private ContentAdmission() {}

    /**
     * True when a REST content stream may start now.
     *
     * @param stream the caller's own name ("books", "zim", "kolibri"), used only so the deferral
     *               reads in logcat the way it did when each provisioner logged for itself
     */
    public static boolean canStart(Context ctx, String stream) {
        if (ModuleQueueRepository.get().isRunning()
                || MapsProvisioner.hasPending(ctx)
                || DashboardRebuildService.isRunning()) {
            Log.d(TAG, stream + " drain deferred: proot (runrole) or dashboard-update work is in flight");
            return false;
        }
        if (PendingContent.anyUnfinished(ctx)) {
            Log.d(TAG, stream + " drain deferred: a content stream still has work to do");
            return false;
        }
        return true;
    }
}
