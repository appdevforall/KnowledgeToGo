/*
 * ============================================================================
 * Name        : DashNodeRequirement.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-430. Per-module minimum dash-node version. Pure data + lookup; the version compare
 *               and the box read live elsewhere (redesign.DashboardVersion), so this stays a plain,
 *               unit-testable registry with no Android or network dependency.
 *
 *               Forgejo needs the dashboard build that ships its repo status/refresh endpoints (1.3.7):
 *               an older dash-node makes "Install repos"/"Update repos" and the seed break silently, so
 *               installing Forgejo is HARD-blocked below that version. Code on the Go add-ons (K2GO-99)
 *               needs the dashboard build that ships the /addons/refresh endpoints (1.3.8), so the gallery
 *               can update, so it is HARD-blocked below that version too. Other modules have no hard minimum
 *               (a soft "update the dashboard" suggestion applies to all installs, handled in the gate).
 * ============================================================================
 */
package org.appdevforall.k2go.dependency.domain;

import androidx.annotation.Nullable;

public final class DashNodeRequirement {
    private DashNodeRequirement() {}

    /** {major, minor, patch} minimum dash-node for {@code moduleKey}, or null when it has no hard minimum. */
    @Nullable
    public static int[] minFor(@Nullable String moduleKey) {
        if ("forgejo".equals(moduleKey)) return new int[]{1, 3, 7};
        if ("code_addons".equals(moduleKey)) return new int[]{1, 3, 8};   // K2GO-99: needs /addons/refresh
        return null;
    }

    /** Human "x.y.z" label for the minimum, or null when there is none. */
    @Nullable
    public static String minLabel(@Nullable String moduleKey) {
        int[] m = minFor(moduleKey);
        return m == null ? null : m[0] + "." + m[1] + "." + m[2];
    }
}
