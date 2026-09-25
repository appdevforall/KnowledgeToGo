/*
 * ============================================================================
 * Name        : ForgejoInstallPrefs.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-417 (part 2). The banked intent to seed the on-device Forgejo
 *               after its runrole installs. The Forgejo role (iiab upstream) ships
 *               empty: K2Go adds the admin, the org and the example repos through the
 *               dash-node seed (ForgejoSeedProvisioner). This store holds "a seed is
 *               owed" plus the user's opt-in for the example repos, so the seed
 *               survives process death and drains when the box is up.
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One banked "seed the box Forgejo" order, per install. {@code pending} is the marker the
 * provisioner drains; {@code includeRepos} is the opt-in for the example repos (the admin and
 * the org are always seeded, so the auto-login user exists even when the repos are declined).
 *
 * <p>Lifecycle (single owner of each transition, so the marker cannot leak):
 * <ul>
 *   <li>SET by the UI when a Forgejo install is committed ({@link #bankSeed}).</li>
 *   <li>CLEARED by the provisioner on a seeded box, on a box with no Forgejo (the install did
 *       not land, so there is nothing to seed), or after {@link #MAX_ATTEMPTS} failed drains
 *       (best-effort gives up rather than re-draining forever).</li>
 * </ul>
 */
public final class ForgejoInstallPrefs {

    private ForgejoInstallPrefs() {}

    private static final String FILE = "k2go_forgejo_prefs";
    private static final String KEY_PENDING = "seed_pending";
    private static final String KEY_INCLUDE_REPOS = "seed_include_repos";
    private static final String KEY_ATTEMPTS = "seed_attempts";

    /** Bounded retry so a permanently failing seed does not re-drain on every box start. */
    public static final int MAX_ATTEMPTS = 3;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Bank a seed for the just-committed Forgejo install. {@code includeRepos} = seed the example repos too. */
    public static void bankSeed(Context ctx, boolean includeRepos) {
        prefs(ctx).edit()
                .putBoolean(KEY_PENDING, true)
                .putBoolean(KEY_INCLUDE_REPOS, includeRepos)
                .putInt(KEY_ATTEMPTS, 0)
                .apply();
    }

    /** True when a seed is owed and has not yet been drained. */
    public static boolean isSeedPending(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PENDING, false);
    }

    /** The opt-in for the example repos captured when the install was committed (default on). */
    public static boolean includeRepos(Context ctx) {
        return prefs(ctx).getBoolean(KEY_INCLUDE_REPOS, true);
    }

    /** Clear the banked seed (drained, nothing to seed, or gave up). */
    public static void clearSeed(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_PENDING)
                .remove(KEY_INCLUDE_REPOS)
                .remove(KEY_ATTEMPTS)
                .apply();
    }

    /** Record one failed drain; returns the new count so the caller can stop at {@link #MAX_ATTEMPTS}. */
    public static int recordFailedAttempt(Context ctx) {
        int next = prefs(ctx).getInt(KEY_ATTEMPTS, 0) + 1;
        prefs(ctx).edit().putInt(KEY_ATTEMPTS, next).apply();
        return next;
    }
}
