/*
 * ============================================================================
 * Name        : ForgejoSeedProvisioner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-417 (part 2). Post-install drain of the banked Forgejo seed. Mirrors
 *               ZimProvisioner: once the system is installed and the box REST API is up, it
 *               asks dash-node to seed Forgejo (admin + org, and the example repos when the
 *               user opted in) through the live server, so no box stop is needed. The seed is
 *               a light, best-effort, box-detached job (ForgejoSeedClient), not a REST content
 *               stream, so it does not go through the durable job engine.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.forgejo.data.ForgejoInstallPrefs;
import org.appdevforall.k2go.forgejo.data.ForgejoSeedClient;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;
import org.appdevforall.k2go.networkpolicy.data.NetworkCostAdmission;
import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicyDecision;
import org.appdevforall.k2go.system.data.InstalledModulesReader;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ForgejoSeedProvisioner {
    private ForgejoSeedProvisioner() {}

    private static final String TAG = "K2Go-Provision";
    private static final String FORGEJO = "forgejo";

    /** Single-flight: one drive at a time, across the ~3s home poll and the install index. */
    private static final AtomicBoolean INFLIGHT = new AtomicBoolean(false);

    /** True when a Forgejo seed is banked and not yet drained. */
    public static boolean hasPending(Context ctx) {
        return ForgejoInstallPrefs.isSeedPending(ctx);
    }

    /**
     * Seed the box Forgejo through dash-node. No-op if nothing is banked, a drive is already
     * running, a proot op is in flight, or the network is metered without consent (the order
     * stays banked and a later pass drains it -- the same "deferred is not a failure" contract
     * the REST provisioners use). Requires the box REST API to be up (checked off the main thread).
     */
    public static void drain(Context ctx) {
        if (!ForgejoInstallPrefs.isSeedPending(ctx)) return;

        // Defer while proot / dashboard-update work holds the box: the runrole that installs Forgejo
        // stops the server, and a rebuild restarts dash-node underneath us. Same deferral ContentAdmission
        // applies to the REST streams (one reason, stated once here for this non-stream drain).
        if (ModuleQueueRepository.get().isRunning()
                || MapsProvisioner.hasPending(ctx)
                || DashboardRebuildService.isRunning()) {
            Log.d(TAG, "forgejo seed deferred: proot (runrole) or dashboard-update work is in flight");
            return;
        }

        // ADR-395 cost gate: the seed's repo clones are a metered-data transfer, so it holds on a
        // metered network without consent (or offline), exactly like the content drains. This is the
        // seed half of "both the runrole and the seed pass the metered gate" -- the runrole passed it at
        // the UI commit (NetworkPolicyGate); the seed passes it here.
        NetworkPolicyDecision cost = NetworkCostAdmission.decideNow(ctx);
        if (cost != NetworkPolicyDecision.ALLOW) {
            Log.d(TAG, "forgejo seed deferred: "
                    + (cost == NetworkPolicyDecision.BLOCKED_NO_NETWORK ? "no usable network" : "metered without consent"));
            return;
        }

        if (!INFLIGHT.compareAndSet(false, true)) return;   // a drive is already running
        final Context app = ctx.getApplicationContext();
        try {
            AppExecutors.get().io().execute(() -> {
            try {
                // The box REST API must answer before we POST (nginx up but dash-node still warming =
                // 502). Not ready -> leave it banked and let a later pass retry; no attempt is spent.
                if (!RestReadiness.apiReady()) {
                    Log.d(TAG, "forgejo seed: REST API not ready yet; will retry");
                    return;
                }
                // Nothing to seed if Forgejo is not on the box: the install did not land (a null read is
                // "could not tell", not "absent", so only a definite absence clears the order).
                Set<String> installed = InstalledModulesReader.installedKeys(app);
                if (installed != null && !installed.contains(FORGEJO)) {
                    Log.i(TAG, "forgejo seed: Forgejo is not installed; clearing the banked seed");
                    ForgejoInstallPrefs.clearSeed(app);
                    return;
                }

                boolean includeRepos = ForgejoInstallPrefs.includeRepos(app);
                Log.i(TAG, "forgejo seed: driving (includeRepos=" + includeRepos + ")");
                ForgejoSeedClient.Result r = new ForgejoSeedClient()
                        .drive(includeRepos, line -> Log.d(TAG, "forgejo seed: " + line));
                if (r == ForgejoSeedClient.Result.DONE) {
                    Log.i(TAG, "forgejo seed: done");
                    ForgejoInstallPrefs.clearSeed(app);
                } else {
                    int attempts = ForgejoInstallPrefs.recordFailedAttempt(app);
                    if (attempts >= ForgejoInstallPrefs.MAX_ATTEMPTS) {
                        Log.w(TAG, "forgejo seed: failed " + attempts + " times; giving up (best-effort)");
                        ForgejoInstallPrefs.clearSeed(app);
                    } else {
                        Log.w(TAG, "forgejo seed: failed (attempt " + attempts + "); will retry on a later pass");
                    }
                }
            } finally {
                INFLIGHT.set(false);
            }
            });
        } catch (RuntimeException e) {
            // The executor rejected the task, so the Runnable's finally will never run: reset the
            // single-flight flag here or the seed would never drain again this process (best-effort).
            INFLIGHT.set(false);
            Log.w(TAG, "forgejo seed: could not dispatch the drive: " + e.getMessage());
        }
    }
}
