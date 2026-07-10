/*
 * ============================================================================
 * Name        : LogRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4640 — app-scoped single source of truth for the server log.
 *               Process-scoped (not tied to a Fragment/Activity), so the log
 *               survives tab switches, hide/show, and recreation. Producers
 *               (server control, install/Ansible output from InstallService, CLI,
 *               app events) append here; the Usage console renders the snapshot
 *               and observes incremental appends. Timestamping is centralized.
 *
 *               Persistence: the buffer is mirrored to a dedicated file
 *               (server_log.txt) with a debounced background write, and reloaded
 *               on process start, so the Module Management (Ansible) log survives
 *               app restarts. This is separate from the watchdog blackbox
 *               (watchdog_heartbeat_log.txt), which keeps its own purpose; there
 *               is no broadcast here, so no feedback loop with the watchdog.
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class LogRepository {

    /** Keep at most this many lines in memory (and therefore on disk). */
    private static final int MAX_LINES = 8000;
    private static final String FILE_NAME = "server_log.txt";
    private static final long FLUSH_DELAY_MS = 400L;

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

    private volatile Context appContext;
    private volatile Handler io;
    private final Runnable flushRunnable = this::writeSnapshotToFile;

    private LogRepository() {
    }

    /**
     * Wire up persistence with the application context (call once from
     * {@code Application.onCreate}). Loads any prior server_log.txt in the
     * background so the console shows the previous session's log.
     */
    public synchronized void init(Context ctx) {
        if (appContext != null || ctx == null) return;
        appContext = ctx.getApplicationContext();
        HandlerThread t = new HandlerThread("LogRepo-io");
        t.start();
        io = new Handler(t.getLooper());
        io.post(this::loadFromFile);
    }

    /** A copy of the current buffer, safe to render (newest last). */
    public synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }

    /**
     * Append one raw line (from any thread). The line is timestamped here and
     * stored; listeners are notified on the main thread and the buffer is
     * scheduled to be persisted.
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
        scheduleFlush();
    }

    /** Clear the buffer (from any thread). Listeners are notified on the main thread. */
    public void clear() {
        synchronized (this) {
            lines.clear();
        }
        main.post(() -> {
            for (Listener l : listenersCopy()) l.onCleared();
        });
        scheduleFlush();
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

    // ---- persistence (background thread) --------------------------------

    private void scheduleFlush() {
        Handler h = io;
        if (h == null) return; // persistence not initialized; in-memory only
        h.removeCallbacks(flushRunnable);
        h.postDelayed(flushRunnable, FLUSH_DELAY_MS);
    }

    private void writeSnapshotToFile() {
        Context ctx = appContext;
        if (ctx == null) return;
        List<String> copy = snapshot();
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f, false))) {
            for (String s : copy) {
                w.write(s);
                w.write("\n");
            }
        } catch (Exception ignored) {
            // best-effort persistence; never crash the app over the log file
        }
    }

    private void loadFromFile() {
        Context ctx = appContext;
        if (ctx == null) return;
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return;
        ArrayList<String> loaded = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) loaded.add(line);
        } catch (Exception e) {
            return;
        }
        if (loaded.isEmpty()) return;
        synchronized (this) {
            lines.addAll(0, loaded); // prior-session lines are older than anything appended since
            int over = lines.size() - MAX_LINES;
            if (over > 0) lines.subList(0, over).clear();
        }
    }
}
