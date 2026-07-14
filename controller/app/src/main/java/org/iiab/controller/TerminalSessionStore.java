/*
 * ============================================================================
 * Name        : TerminalSessionStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped owner of the ninja terminal sessions (ADFA-4696,
 *               phase 2). The sessions and the "current" pointer live here,
 *               outside the Activity, so they are not lost when the Activity is
 *               destroyed. This store IS the TerminalSessionClient for every
 *               session, so shell I/O keeps being serviced even while no UI is
 *               attached (e.g. the app is backgrounded). UI-facing events are
 *               forwarded to an optional, detachable Ui delegate that the
 *               TerminalController registers while it is on screen and releases
 *               in onDestroy, so the store never pins a dead Activity.
 *
 *               Termux delivers these client callbacks on the main thread, so
 *               list mutations here are single-threaded with the drawer adapter.
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

public final class TerminalSessionStore implements TerminalSessionClient {

    /** UI-facing callbacks. Implemented by TerminalController while it is on screen. */
    public interface Ui {
        void onInvalidate();
        void onSessionsChanged();
        /** The current session ended; newCurrent is the session to show, or null if none left. */
        void onCurrentSessionEnded(TerminalSession newCurrent);
        void copyToClipboard(String text);
        void pasteInto(TerminalSession target);
    }

    private static TerminalSessionStore instance;

    public static synchronized TerminalSessionStore get() {
        if (instance == null) instance = new TerminalSessionStore();
        return instance;
    }

    private final List<TerminalSession> sessions = new ArrayList<>();
    private TerminalSession current;
    private Ui ui; // nullable: null while no UI is attached
    // App context (never an Activity) so the keep-alive service can be stopped
    // even when no UI is attached (e.g. the last shell exits while backgrounded).
    private Context appContext;

    private TerminalSessionStore() {}

    public List<TerminalSession> sessions() { return sessions; }
    public TerminalSession current() { return current; }
    public void setCurrent(TerminalSession session) { current = session; }
    public boolean isEmpty() { return sessions.isEmpty(); }

    public void attachUi(Ui ui) { this.ui = ui; }
    public void detachUi(Ui ui) { if (this.ui == ui) this.ui = null; }

    /** Provide an application Context once, so the store can own service stop/start. */
    public void init(Context ctx) { if (appContext == null && ctx != null) appContext = ctx.getApplicationContext(); }

    // ---- TerminalSessionClient ------------------------------------------------

    @Override
    public void onTextChanged(TerminalSession changed) {
        Ui u = ui;
        if (u != null && changed == current) u.onInvalidate();
    }

    @Override
    public void onTitleChanged(TerminalSession session) {
    }

    @Override
    public void onSessionFinished(TerminalSession finished) {
        sessions.remove(finished);
        boolean wasCurrent = (finished == current);
        if (wasCurrent) {
            current = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        }
        // Own the keep-alive lifecycle here so it is stopped even with no UI attached.
        if (sessions.isEmpty() && appContext != null) {
            TerminalSessionService.stop(appContext);
        }
        Ui u = ui;
        if (u != null) {
            if (wasCurrent) u.onCurrentSessionEnded(current);
            else u.onSessionsChanged();
        }
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        Ui u = ui;
        if (u != null) u.copyToClipboard(text);
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        Ui u = ui;
        if (u != null) u.pasteInto(session != null ? session : current);
    }

    @Override
    public void onBell(TerminalSession session) {
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return 0;
    }

    @Override
    public void setTerminalShellPid(TerminalSession session, int pid) {
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "Stack trace", e);
    }
}
