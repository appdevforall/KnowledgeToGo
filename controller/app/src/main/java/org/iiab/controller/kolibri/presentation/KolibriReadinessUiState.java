/*
 * ============================================================================
 * Name        : KolibriReadinessUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable view state for "what should happen to this order"
 *               (ADFA-4954, on the ADR-5061 model).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import org.iiab.controller.kolibri.domain.InstalledLibrary;
import org.iiab.controller.system.domain.OperationDispatcher.Dispatch;
import org.iiab.controller.system.domain.SystemFacts;

/**
 * The dispatcher's answer, plus enough context to say it in words.
 *
 * <p>Carries the facts as well as the verdict because the two are not the same
 * message: "not on offer" and "the box is off" both stop a download, but only one
 * of them is the user's to fix. A screen that has only the verdict ends up telling
 * everyone the same unhelpful thing.
 *
 * <p>Immutable.
 */
public final class KolibriReadinessUiState {

    private static final KolibriReadinessUiState CHECKING =
            new KolibriReadinessUiState(true, null, null, InstalledLibrary.unknown());

    private final boolean checking;
    private final Dispatch dispatch;
    private final SystemFacts facts;
    private final InstalledLibrary library;

    private KolibriReadinessUiState(boolean checking, Dispatch dispatch,
                                    SystemFacts facts, InstalledLibrary library) {
        this.checking = checking;
        this.dispatch = dispatch;
        this.facts = facts;
        this.library = library;
    }

    static KolibriReadinessUiState checking() {
        return CHECKING;
    }

    static KolibriReadinessUiState resolved(Dispatch dispatch, SystemFacts facts,
                                            InstalledLibrary library) {
        return new KolibriReadinessUiState(false, dispatch, facts,
                library == null ? InstalledLibrary.unknown() : library);
    }

    /** Still asking. The forward action stays disabled rather than guessing. */
    public boolean isChecking() {
        return checking;
    }

    /** The answer, or null while still checking. */
    public Dispatch dispatch() {
        return dispatch;
    }

    /** The state of the box behind the answer. Null while checking. */
    public SystemFacts facts() {
        return facts;
    }

    /** What the device already holds. Never null; may be unknown. */
    public InstalledLibrary library() {
        return library;
    }

    /** The order is taken now and carried out after the system is installed. */
    public boolean isDeferred() {
        return dispatch == Dispatch.DEFER;
    }

    /** The order can be carried out, now or once the box has been started. */
    public boolean canRun() {
        return dispatch == Dispatch.RUN_LIVE
                || dispatch == Dispatch.ENSURE_SERVER_THEN_RUN_LIVE;
    }

    /** The box has to be brought up first. */
    public boolean needsServerStart() {
        return dispatch == Dispatch.ENSURE_SERVER_THEN_RUN_LIVE;
    }

    /** Something can be done with the order — queued or run. */
    public boolean isActionable() {
        return isDeferred() || canRun();
    }
}
