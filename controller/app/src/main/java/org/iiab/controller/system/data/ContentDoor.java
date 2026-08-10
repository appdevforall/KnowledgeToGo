/*
 * ============================================================================
 * Name        : ContentDoor.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Whether a content screen should bank its order or run it now —
 *               asked of the facts, not of the door it came through (ADFA-5061).
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;

import org.iiab.controller.system.domain.ContentType;
import org.iiab.controller.system.domain.Operation;
import org.iiab.controller.system.domain.OperationDispatcher;
import org.iiab.controller.system.domain.SystemFacts;

/**
 * The one question every content confirm screen has to answer: <b>write this order
 * down, or carry it out now?</b>
 *
 * <p><b>What this replaces.</b> Four booleans on {@code SetupLibraryActivity} —
 * {@code zimWizard}, {@code mapsWizard}, {@code booksWizard}, {@code kolibriWizard} —
 * each set when a flow was opened from the wizard's content step and each read by
 * that flow's Confirm screen. They did not describe the system; they described
 * <em>which door the user walked through</em>, and they were plain fields, so a
 * rotation or a theme toggle lost them. What came back was a screen that believed it
 * was on the live path and tried to download against a system that did not exist yet.
 *
 * <p>The honest question was never "which door was this?" but "is there a box to run
 * against?", and {@link OperationDispatcher} already answers it from
 * {@link SystemFacts}. Facts survive a recreation because they are re-read; a field
 * does not.
 *
 * <p><b>Scope of this first pass.</b> Only {@link OperationDispatcher.Dispatch#DEFER}
 * changes behaviour — that is the bank-instead-of-run case the booleans stood for.
 * The dispatcher's other refusals are deliberately <em>not</em> acted on here yet:
 * today these screens download regardless of a damaged system or an absent platform,
 * and turning that into a refusal is a real UX change that belongs with the UX
 * contract (ADR-5061, migration item 2d), not smuggled in under a flag removal.
 * {@link #dispatch} exposes the full answer for the screen that is ready for it —
 * Courses already is.
 */
public final class ContentDoor {

    private ContentDoor() {
    }

    /**
     * The full verdict for adding content of this type right now.
     *
     * @param replacementPending whether the caller sits inside a flow that is going
     *                           to install or replace the system. Not a fact the
     *                           device can be asked: during a reinstall the old box
     *                           is installed, healthy and answering right up to the
     *                           moment it is wiped, so reading only the facts would
     *                           download onto a system with its days numbered. The
     *                           wizard activity knows, because it holds the Intent it
     *                           was started with.
     */
    public static OperationDispatcher.Dispatch dispatch(Context ctx, ContentType type,
                                                        boolean replacementPending) {
        if (ctx == null || type == null) {
            return OperationDispatcher.Dispatch.UNAVAILABLE;
        }
        SystemFacts facts = SystemFactsReader.read(ctx);
        if (replacementPending) {
            facts = facts.withReplacementPending();
        }
        Operation op = type.operation();
        // Platform presence is asserted rather than probed: these screens have never
        // checked it, and answering UNAVAILABLE here would refuse downloads that work
        // today. The probe belongs with the UX contract that can explain the refusal.
        return OperationDispatcher.resolve(op, facts, true);
    }

    /**
     * Whether the order should be written down for later instead of run now.
     *
     * <p>The direct replacement for {@code isZimWizard()} and friends, and the only
     * part of the verdict that changes what these screens do today.
     */
    public static boolean banks(Context ctx, ContentType type, boolean replacementPending) {
        return OperationDispatcher.isDeferred(dispatch(ctx, type, replacementPending));
    }
}
