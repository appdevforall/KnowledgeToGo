/*
 * ============================================================================
 * Name        : LowMemoryAdvice.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-557. The single rule for "should we advise the user to close apps before this
 *               dash-node update". The update stages a second node and a tsc build next to the live
 *               server, so it needs working memory. We advise ONLY when free memory is at or below
 *               what the update needs: with clear headroom we stay silent (no nagging). The advice is
 *               advisory, never a gate: the update still runs. Pure domain, unit-tested.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.domain;

public final class LowMemoryAdvice {
    private LowMemoryAdvice() {}

    /**
     * Working memory the live update needs (the staging node plus the tsc build, beside the live
     * server). Measured on a 64-bit device for K2GO-557: a cold build (the box was idle, which is the
     * real user case) consumes about 386 MB and also uses swap. This value is that peak plus a small
     * margin. We advise only at or below it, so a device with clear headroom is left alone. Tune it
     * again from field results. A 32-bit device runs lighter per process but has far less headroom.
     */
    public static final long UPDATE_WORKING_SET_BYTES = 450L * 1024L * 1024L;   // about 450 MB

    /** Advise (advisory only) when the system already reports memory pressure, or when free memory is
     *  at or below what the update needs. */
    public static boolean shouldAdviseCloseApps(DeviceMemory memory) {
        if (memory == null) return false;
        if (memory.systemLowMemory()) return true;
        return memory.availableBytes() <= UPDATE_WORKING_SET_BYTES;
    }
}
