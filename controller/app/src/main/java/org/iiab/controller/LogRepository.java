/*
 * ============================================================================
 * Name        : LogRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4640 — app-scoped single source of truth for the server log.
 *               Process-scoped (not tied to a Fragment/Activity), so the log
 *               survives tab switches (ViewPager2 destroys offscreen fragments),
 *               hide/show of the console, and configuration-change recreation.
 *               Producers (server control, install/Ansible output from
 *               InstallService, CLI, app events) append here; the Usage console
 *               renders the snapshot and observes incremental appends. Timestamping
 *               is centralized here so every source is stamped consistently.
 * ============================================================================
 */
package org.iiab.controller;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class LogRepository {

    /** Keep at most this many lines in memory (bounded ring buffer). */
    private static final int MAX_LINES = 8000;

    private static final LogRepository INSTANCE = new LogRepository();

    public static LogRepository get() {
        return INSTANCE;
    }

    /** Observer of live log changes. Callbacks are delivered on the main thread. */
    public interface Listener {
        void onAppend(String line);
        void onCleared();
    }

    private final ArrayList<String> lines = new ArrayList<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LogRepository() {
    }

    /** A copy of the current buffer, safe to render (newest last). */
    public synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }

    /**
     * Append one raw line (from any thread). The line is timestamped here and
     * stored; listeners are notified on the main thread.
     */
    public void append(String rawLine) {
        if (rawLine == null) return;
        String stamped = "[" + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
                + "] " + rawLine;
        synchronized (this) {
            lines.add(stamped);
            int over = lines.size() - MAX_LINES;
            if (over > 0) lines.subList(0, over).clear();
        }
        main.post(() -> {
            for (Listener l : listenersCopy()) l.onAppend(stamped);
        });
    }

    /** Clear the buffer (from any thread). Listeners are notified on the main thread. */
    public void clear() {
        synchronized (this) {
            lines.clear();
        }
        main.post(() -> {
            for (Listener l : listenersCopy()) l.onCleared();
        });
    }

    public synchronized void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    private synchronized List<Listener> listenersCopy() {
        return new ArrayList<>(listeners);
    }
}
