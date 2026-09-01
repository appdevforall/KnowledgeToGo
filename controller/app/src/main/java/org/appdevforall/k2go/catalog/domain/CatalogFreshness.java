/*
 * ============================================================================
 * Name        : CatalogFreshness.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Pure rules for the catalog refresh:
 *               when to check for an update, and whether the published catalog
 *               differs from the one in use. The app stores, per catalog, the
 *               active catalog's hash and the last successful check time; these
 *               rules decide when to hit the network and when to swap. No
 *               Android, no I/O => unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.catalog.domain;

public final class CatalogFreshness {

    /** How long before the app bothers checking the manifest again. */
    public static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private CatalogFreshness() {
    }

    /**
     * True when the catalog is due for an update check: never checked
     * ({@code lastCheckMs <= 0}) or more than {@code ttlMs} has elapsed. A clock that ran
     * backwards ({@code now < lastCheck}) is treated as due, so a wrong device clock never wedges
     * the check off.
     */
    public static boolean dueForCheck(long lastCheckMs, long nowMs, long ttlMs) {
        if (lastCheckMs <= 0L) return true;
        if (nowMs < lastCheckMs) return true;
        return nowMs - lastCheckMs >= ttlMs;
    }

    /**
     * True when the published catalog differs from the one the app is using, i.e. it should be
     * pulled and swapped. The hash is the source of truth (the version string is only a label):
     * an absent remote hash means "cannot tell" — do not swap; a local hash that differs from a
     * present remote — swap; no local hash yet (never pulled) with a real remote — swap.
     */
    public static boolean changed(String localHash, String remoteHash) {
        if (remoteHash == null || remoteHash.isEmpty()) return false;
        if (localHash == null || localHash.isEmpty()) return true;
        return !localHash.equals(remoteHash);
    }
}
