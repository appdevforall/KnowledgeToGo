/*
 * ============================================================================
 * Name        : RunSnapshot.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434. The inputs the run verdict rule needs, captured as a plain value (no
 *               Android, no services). SetupProgressActivity.render() gathers these from its
 *               repositories/services each pass; RunVerdict turns them into working/success/failure.
 *               Built with the nested Builder because there are many independent boolean inputs and
 *               positional arguments would be easy to transpose. Slice 1 of
 *               controller/docs/ADR-434-setupprogress-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.setup.domain;

public final class RunSnapshot {
    /** No live REST content in this run (a proot-only set). Selects the completion rule. */
    public final boolean noRest;
    /** A proot stage (maps or a module batch) is part of this run. */
    public final boolean prootShown;
    /** A non-maps module batch is part of this run. */
    public final boolean moduleShown;
    /** The REST pipeline has started every stage it was going to start. */
    public final boolean drained;
    /** The proot queue is terminal and not running. */
    public final boolean queueTerminalNotRunning;
    /** The post-batch server restart settled: the server came up, or the wait gave up. */
    public final boolean moduleServerSettled;
    /** The post-batch server did not come up within the wait: a failure, even with no failed item. */
    public final boolean batchServerSlow;
    /** A Forgejo seed still needs to run (or is running) in this run, and can make progress. */
    public final boolean seedPendingRun;
    public final StreamState zim;
    public final StreamState books;
    public final StreamState kolibri;
    /** Failed proot runroles that belong to this run. */
    public final int prootFailed;
    /** The Forgejo seed of this run gave up. */
    public final boolean forgejoSeedFailed;

    private RunSnapshot(Builder b) {
        this.noRest = b.noRest;
        this.prootShown = b.prootShown;
        this.moduleShown = b.moduleShown;
        this.drained = b.drained;
        this.queueTerminalNotRunning = b.queueTerminalNotRunning;
        this.moduleServerSettled = b.moduleServerSettled;
        this.batchServerSlow = b.batchServerSlow;
        this.seedPendingRun = b.seedPendingRun;
        this.zim = b.zim;
        this.books = b.books;
        this.kolibri = b.kolibri;
        this.prootFailed = b.prootFailed;
        this.forgejoSeedFailed = b.forgejoSeedFailed;
    }

    public static final class Builder {
        private boolean noRest, prootShown, moduleShown, drained;
        private boolean queueTerminalNotRunning, moduleServerSettled, batchServerSlow, seedPendingRun;
        private StreamState zim = new StreamState(false, false, 0);
        private StreamState books = new StreamState(false, false, 0);
        private StreamState kolibri = new StreamState(false, false, 0);
        private int prootFailed;
        private boolean forgejoSeedFailed;

        public Builder noRest(boolean v) { this.noRest = v; return this; }
        public Builder prootShown(boolean v) { this.prootShown = v; return this; }
        public Builder moduleShown(boolean v) { this.moduleShown = v; return this; }
        public Builder drained(boolean v) { this.drained = v; return this; }
        public Builder queueTerminalNotRunning(boolean v) { this.queueTerminalNotRunning = v; return this; }
        public Builder moduleServerSettled(boolean v) { this.moduleServerSettled = v; return this; }
        public Builder batchServerSlow(boolean v) { this.batchServerSlow = v; return this; }
        public Builder seedPendingRun(boolean v) { this.seedPendingRun = v; return this; }
        public Builder zim(StreamState v) { this.zim = v; return this; }
        public Builder books(StreamState v) { this.books = v; return this; }
        public Builder kolibri(StreamState v) { this.kolibri = v; return this; }
        public Builder prootFailed(int v) { this.prootFailed = v; return this; }
        public Builder forgejoSeedFailed(boolean v) { this.forgejoSeedFailed = v; return this; }
        public RunSnapshot build() { return new RunSnapshot(this); }
    }
}
