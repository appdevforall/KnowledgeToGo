/*
 * ============================================================================
 * Name        : DashboardCardState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5339. The one rule that turns an update-check outcome into what the Dashboard
 *               (REST core) card shows — the primary action (Update vs Rebuild), the status pill, and
 *               the version subtitle. It lives here, once, because two surfaces render it (the
 *               module-hub row and the detail card); without a shared source they drift. Pure JVM
 *               (no android.*), so the rule is unit-tested off device and the fragments only paint it.
 * ============================================================================
 */
package org.appdevforall.k2go.dashboard.domain;

/**
 * Resolves the Dashboard card's state from a connectivity flag, the live update-check result (or its
 * absence), and the last-known cached value.
 *
 * <p>The card answers one question — "is a newer REST-core build available, and can I act on it?" —
 * and four states cover it:
 * <ul>
 *   <li>{@link Kind#OFFLINE} — no network. There is no honest Update affordance (a rebuild needs the
 *       internet to fetch), so the card says "No connection" rather than offering an action it can't
 *       complete. Offline wins over any cached value for exactly this reason.</li>
 *   <li>{@link Kind#UPDATE_AVAILABLE} — a newer build exists; the primary action is Update.</li>
 *   <li>{@link Kind#UP_TO_DATE} — on the latest; the primary action is a manual Rebuild
 *       (de-emphasized).</li>
 *   <li>{@link Kind#CHECKING} — online, no result yet and nothing cached to fall back on.</li>
 * </ul>
 *
 * <p>The version subtitle ("v1.2.7 → v1.3.0") needs both versions, which only a <em>live</em> check
 * carries — the cache stores just the boolean (ADFA-5026). A cached UPDATE_AVAILABLE therefore shows
 * the state without the arrow; {@link #targetVersion()} is null there, and the caller omits it.
 */
public final class DashboardCardState {

    public enum Kind { CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, OFFLINE }

    private final Kind kind;
    private final String installedVersion;   // may be null/empty when unknown
    private final String targetVersion;      // non-null only for a LIVE UPDATE_AVAILABLE

    private DashboardCardState(Kind kind, String installedVersion, String targetVersion) {
        this.kind = kind;
        this.installedVersion = installedVersion;
        this.targetVersion = targetVersion;
    }

    /**
     * @param online         whether the device has an internet-capable network
     * @param liveOk         whether a live update-check just succeeded (false = it errored or hasn't run)
     * @param liveUpdate     the live check's "newer build available" result (only read when liveOk)
     * @param installed      the installed version from the live check (for the subtitle), or null
     * @param available      the available version from the live check (for the subtitle), or null
     * @param hasCache       whether a previous successful check left a cached value
     * @param cachedUpdate   the cached "update available" flag (only read when hasCache and not liveOk)
     */
    public static DashboardCardState resolve(boolean online, boolean liveOk, boolean liveUpdate,
                                             String installed, String available,
                                             boolean hasCache, boolean cachedUpdate) {
        if (!online) {
            return new DashboardCardState(Kind.OFFLINE, installed, null);
        }
        if (liveOk) {
            return liveUpdate
                    ? new DashboardCardState(Kind.UPDATE_AVAILABLE, installed, available)
                    : new DashboardCardState(Kind.UP_TO_DATE, installed, null);
        }
        if (hasCache) {
            // Online but the check failed (box stopped): fall back to the last-known state. The cache
            // holds only the boolean, so an available UPDATE_AVAILABLE here has no target version.
            return cachedUpdate
                    ? new DashboardCardState(Kind.UPDATE_AVAILABLE, installed, null)
                    : new DashboardCardState(Kind.UP_TO_DATE, installed, null);
        }
        return new DashboardCardState(Kind.CHECKING, installed, null);
    }

    public Kind kind() { return kind; }

    /** The primary button is "Update" only when a newer build is available; otherwise "Rebuild". */
    public boolean primaryIsUpdate() { return kind == Kind.UPDATE_AVAILABLE; }

    /** The card shows a "No connection" affordance instead of an actionable state. */
    public boolean isOffline() { return kind == Kind.OFFLINE; }

    /** The installed version, for the subtitle; may be null/empty when unknown. */
    public String installedVersion() { return installedVersion; }

    /** The target version for the "vX → vY" subtitle, or null when it should not be shown (every
     *  state but a LIVE UPDATE_AVAILABLE). Present iff {@link #showsVersionArrow()}. */
    public String targetVersion() { return targetVersion; }

    /** True when both versions are known and differ, so the "vX → vY" subtitle is worth showing. */
    public boolean showsVersionArrow() {
        return targetVersion != null && !targetVersion.isEmpty()
                && installedVersion != null && !installedVersion.isEmpty()
                && !targetVersion.equals(installedVersion);
    }
}
