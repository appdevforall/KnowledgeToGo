/*
 * ============================================================================
 * Name        : SystemDoor.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Whether a STOPPED run may start now — asked of the facts, the
 *               way ContentDoor already asks for the live side (ADFA-5061).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;

import org.iiab.controller.system.domain.Operation;
import org.iiab.controller.system.domain.OperationDispatcher;
import org.iiab.controller.system.domain.SystemFacts;

/**
 * The one question every module drain has to answer: <b>may this run start now?</b>
 *
 * <p><b>Why this exists.</b> {@code ContentDoor} put the live half of the model to work months
 * ago; the stopped half — the operation that actually takes the box down — never asked it.
 * {@code OperationDispatcher.resolve} had exactly two callers in production, both LIVE, and
 * {@code Operation.appInstall(...)} was used only to decide whether a row in a bottom sheet
 * showed a warning. A model that answers for one of its two execution classes is a description,
 * not a source of truth.
 *
 * <p><b>What it changes at the door.</b> {@code ModuleProvisioner.drain} and
 * {@code MapsProvisioner.drain} checked two things: is the wishlist non-empty, and is a queue
 * already running. Neither is about the box. So a drain would hand modules to
 * {@code InstallService} over a system that is half-installed and will not boot — the runrole
 * fails inside the proot and the user gets a second failure to explain instead of the repair —
 * and over a system that does not exist yet, where the honest answer is to leave the order
 * banked. The dispatcher already knew all of that.
 *
 * <p><b>The refusal keeps the order.</b> A refused drain returns before clearing the wishlist,
 * so nothing is lost: the modules stay banked and the next drain — after the repair, after the
 * install — picks them up. That is the same shape the content side uses for DEFER.
 *
 * <p><b>The platform argument, honestly.</b> For an {@code APP_INSTALL} the dispatcher's answer
 * does not depend on which platform is being installed; it is a fact about the box, so one call
 * answers for a whole batch. The name is passed anyway, because it is what a log line needs to
 * be useful and because a per-platform rule (a tier that does not carry a module, say) would
 * land here rather than in the caller.
 *
 * <p>Not the whole gate. {@code EnvironmentLock.Owner.MODULE} exists and nothing acquires it,
 * so a clone or a deep operation can still collide with a module run; that is its own ticket.
 * This closes the question the model was built to answer, not every question at this door.
 */
public final class SystemDoor {

    private SystemDoor() {
    }

    /**
     * The full verdict for installing a module right now.
     *
     * @param platform the module key, for the log and for any future per-platform rule
     * @return never null
     */
    public static OperationDispatcher.Dispatch dispatch(Context ctx, String platform) {
        if (ctx == null) {
            return OperationDispatcher.Dispatch.UNAVAILABLE;
        }
        SystemFacts facts = SystemFactsReader.read(ctx);
        // platformPresent is irrelevant to an APP_INSTALL and the dispatcher says so: the
        // install is what puts the platform there, so requiring it would be circular.
        return OperationDispatcher.resolve(Operation.appInstall(platform), facts, true);
    }

    /**
     * Whether the drain may hand this batch to the install engine.
     *
     * <p>The direct counterpart of {@code ContentDoor.banks}, inverted because this side is
     * asking permission rather than asking where to put the order.
     */
    public static boolean mayRunNow(Context ctx, String platform) {
        return OperationDispatcher.mayRunStopped(dispatch(ctx, platform));
    }
}
