package org.appdevforall.k2go.networkpolicy.data;

import android.content.Context;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicy;
import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicyDecision;

/**
 * The one headless "may a heavy transfer start now, cost-wise?" decision (ADR-395).
 *
 * <p>It is the single source of the classify + consent + policy glue, used by both
 * the UI gate ({@code NetworkPolicyGate}, which needs the full decision to choose
 * dialog vs snackbar) and the content-stream admission ({@code ContentAdmission},
 * which needs only the boolean). Data-layer, so the system-side admission can call
 * it without depending on presentation.
 */
public final class NetworkCostAdmission {

    private NetworkCostAdmission() {}

    private static final NetworkPolicy POLICY = new NetworkPolicy();

    /** The full decision for the active default network and the current session consent. */
    @NonNull
    public static NetworkPolicyDecision decideNow(@NonNull Context ctx) {
        return POLICY.decideHeavyStart(
                AndroidNetworkClassifier.classify(ctx),
                SessionMeteredConsentStore.get().isGranted());
    }

    /**
     * True when a heavy transfer may start now on cost grounds (unmetered, or the
     * user consented this session). False means HOLD: leave the order banked, a
     * later pass takes it once the network is free or consent is given -- the same
     * "deferred is not a failure" contract the other admission checks use.
     */
    public static boolean allowsHeavyStartNow(@NonNull Context ctx) {
        return decideNow(ctx) == NetworkPolicyDecision.ALLOW;
    }
}
