/*
 * ============================================================================
 * Name        : ForgejoSeedRepository.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-423. Observable session state for the Forgejo seed shown as a
 *               visible chained step (see ForgejoSeedService). The install index
 *               (SetupProgressActivity) polls it each tick for the row and the
 *               completion gate; the detail fragment polls it for the phase and the
 *               terminal log box. One seed runs at a time, so a single session holds.
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.presentation;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Single, process-scoped session for the Forgejo seed. The seed is ONE unit of work with a coarse
 * state (running/done/error) plus a streamed log tail: unlike the content streams (one job per
 * item) there is no per-item array, so this holds a phase, a running "repos seeded" count derived
 * from the log, and the log tail. Updated from the service IO thread; read from the main thread, so
 * every accessor is synchronized and the log is copied out.
 */
public final class ForgejoSeedRepository {

    /** Coarse lifecycle, mirroring the box seed states the client reports. */
    public enum Status { IDLE, ACTIVE, DONE, FAILED }

    private static final ForgejoSeedRepository INSTANCE = new ForgejoSeedRepository();
    private static final int MAX_LOG = 200;

    public static ForgejoSeedRepository get() { return INSTANCE; }

    private ForgejoSeedRepository() {}

    private Status status = Status.IDLE;
    private boolean includeRepos;
    private int reposSeeded;
    private String lastLine = "";
    private final List<String> log = new ArrayList<>();

    /** Open a fresh session (clears any prior log). */
    public synchronized void startSession(boolean includeRepos) {
        this.status = Status.ACTIVE;
        this.includeRepos = includeRepos;
        this.reposSeeded = 0;
        this.lastLine = "";
        this.log.clear();
    }

    /** Append one streamed status-tail line, keeping the running repo count in step. */
    public synchronized void appendLog(@NonNull String line) {
        if (line.isEmpty()) return;
        lastLine = line;
        // The orchestration logs "seeded <owner>/<name>" once per repo it finishes; count those so
        // the UI can show progress without the box having to emit a total (which it does not).
        if (line.startsWith("seeded ")) reposSeeded++;
        log.add(line);
        while (log.size() > MAX_LOG) log.remove(0);
    }

    /** Terminal: the box reported the seed done, or the drive gave up. */
    public synchronized void finish(boolean ok) {
        status = ok ? Status.DONE : Status.FAILED;
    }

    /** Drop the session so a later run starts clean (called from goHome on a settled run). */
    public synchronized void clearSession() {
        status = Status.IDLE;
        reposSeeded = 0;
        lastLine = "";
        log.clear();
    }

    // ---- reads (main thread) ----------------------------------------------

    public synchronized boolean hasSession() { return status != Status.IDLE; }
    public synchronized boolean isRunning()  { return status == Status.ACTIVE; }
    public synchronized boolean isComplete() { return status == Status.DONE || status == Status.FAILED; }
    public synchronized boolean isFailed()   { return status == Status.FAILED; }
    public synchronized boolean includeRepos() { return includeRepos; }
    public synchronized int reposSeeded()    { return reposSeeded; }
    public synchronized String lastLine()    { return lastLine; }

    /** A copy of the current log tail, oldest first. */
    public synchronized List<String> logLines() { return new ArrayList<>(log); }
}
