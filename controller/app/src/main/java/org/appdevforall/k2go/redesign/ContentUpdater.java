/*
 * ============================================================================
 * Name        : ContentUpdater.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-503. The small contract a module's updater exposes to the shared
 *               ContentUpdaterView, so the inline progress block, the "checking" state, the
 *               speed/ETA line, the terminal message, and the listener lifecycle live in ONE place
 *               instead of being copied per module. A module provides a thin adapter over its
 *               download service; the view never talks to a module service directly. A new download
 *               module inherits the whole updater UX by implementing this interface.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;

public interface ContentUpdater {

    // ---- control (the service owns the foreground job; these just post intents) -----------------
    void start(Context ctx);
    void pause(Context ctx);
    void resume(Context ctx);
    void cancel(Context ctx);
    void retry(Context ctx);
    void finishSession();
    /** Observe session changes on the main thread; null detaches. */
    void setListener(Runnable onUpdate);

    // ---- observed state (read from the shared job; the view never re-derives it) -----------------
    boolean isRunning();
    boolean isPaused();
    boolean hasSession();
    boolean isComplete();
    boolean hasFailed();
    /** -1 while indeterminate (checking / staging), else 0..100. */
    int percent();
    /** Bytes per second (0 when unknown or not a byte transfer). */
    long speed();
    /** Whole-job seconds remaining, or -1 when unknown. */
    long etaSeconds();
    int reconnectAttempt();
    int reconnectTotal();
    /** True when the finished job reported the content was already current (box detail "up to date"). */
    boolean lastWasUpToDate();
    /** The current item / repo the box is working on, for modules that show it instead of speed. */
    String currentDetail();

    // ---- capabilities (an aria2 download vs a git refresh) --------------------------------------
    boolean supportsPauseResume();
    boolean supportsSpeedEta();

    // ---- per-module strings ---------------------------------------------------------------------
    int titleRes();       // "Updating <module>..."
    int doneRes();        // "<module> updated"
    int upToDateRes();    // "<module> already up to date"
    int failedRes();      // "Could not update <module>"
    int cancelledRes();   // "<module> update cancelled"
}
