/*
 * ============================================================================
 * Name        : RunScope.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434. "What work belongs to this run" as a small monotonic latch set, extracted
 *               from SetupProgressActivity. Each stage (maps, module batch, Forgejo seed, dashboard
 *               rebuild) latches true the first time a signal for it is seen and STAYS true for the
 *               run, so a reopened setup screen still renders the stage and reaches completion. The
 *               Activity computes each per-tick signal from its live sources (that read is
 *               Android-coupled and stays there); this holds only the latched state. Pure JVM, no
 *               Android. Slice 2 of controller/docs/ADR-434-setupprogress-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.setup.domain;

public final class RunScope {
    private boolean maps;
    private boolean module;
    private boolean forgejoSeed;
    private boolean rebuild;

    /** OR the signal into the latch and return the (possibly newly) latched value. */
    public boolean latchMaps(boolean signal) { return maps |= signal; }
    public boolean latchModule(boolean signal) { return module |= signal; }
    public boolean latchForgejoSeed(boolean signal) { return forgejoSeed |= signal; }
    public boolean latchRebuild(boolean signal) { return rebuild |= signal; }

    /** Read the current latch without changing it (for a short-circuit before computing a signal). */
    public boolean maps() { return maps; }
    public boolean module() { return module; }
    public boolean forgejoSeed() { return forgejoSeed; }
    public boolean rebuild() { return rebuild; }
}
