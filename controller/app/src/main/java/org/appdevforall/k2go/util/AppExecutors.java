/*
 * ============================================================================
 * Name        : AppExecutors.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Shared app-wide executors (Phase 2 concurrency foundation,
 *               ADFA-4456..4460). Replaces ad-hoc `new Thread()` with pooled,
 *               daemon-threaded executors: io() for one-off background work and
 *               scheduler() for periodic, cancellable polling.
 * ============================================================================
 */
package org.appdevforall.k2go.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    public static AppExecutors get() {
        return INSTANCE;
    }

    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;

    private AppExecutors() {
        io = Executors.newCachedThreadPool(named("iiab-io"));
        scheduler = Executors.newScheduledThreadPool(1, named("iiab-scheduler"));
    }

    /** Pooled executor for one-off background work. */
    public ExecutorService io() {
        return io;
    }

    /** Scheduler for periodic, cancellable tasks (returns a ScheduledFuture). */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    private static ThreadFactory named(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix);
            t.setDaemon(true);
            return t;
        };
    }
}
