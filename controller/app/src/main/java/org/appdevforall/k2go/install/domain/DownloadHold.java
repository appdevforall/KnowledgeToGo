/*
 * ============================================================================
 * Name        : DownloadHold.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-593. When the app must stop a module download on the box
 *               because of the network, and when it may continue it. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

import org.appdevforall.k2go.networkpolicy.domain.NetworkClass;
import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicy;
import org.appdevforall.k2go.networkpolicy.domain.NetworkPolicyDecision;

/**
 * The box downloads on the phone's default network, and its aria2 retry loop continues on mobile
 * data after a Wi-Fi drop, with no question (seen on a Samsung A16 with 4G). The start gate
 * (NetworkPolicyGate) asks only at the start. This rule is the in-flight half: hold the download while
 * the network may not be used, and continue it when it may.
 *
 * <p>A pause by the user is the user's decision: a network event never continues it, and this rule
 * never holds it again. No network at all is not a hold: the box reconnects on its own, and there is
 * no traffic to stop.
 */
public final class DownloadHold {

    private DownloadHold() {}

    /** Who stopped the download, if somebody did. */
    public enum StoppedBy { NOBODY, USER, NETWORK }

    /** Why the download is held. */
    public enum Reason { METERED_NO_CONSENT, KEPT_OFFLINE }

    /** What the caller does now. */
    public enum Action { NONE, HOLD, CONTINUE }

    /**
     * @param net            cost class of the default network now
     * @param meteredConsent the user allowed mobile data for this session
     * @param onlineAllowed  the user allows module installs to connect (online, or allowed this time)
     * @param stoppedBy      who stopped the download, if somebody did
     */
    public static Action decide(NetworkClass net, boolean meteredConsent, boolean onlineAllowed,
                                StoppedBy stoppedBy) {
        if (stoppedBy == StoppedBy.USER || net == NetworkClass.NONE) return Action.NONE;
        boolean mayRun = reason(net, meteredConsent, onlineAllowed) == null;
        if (stoppedBy == StoppedBy.NOBODY) return mayRun ? Action.NONE : Action.HOLD;
        return mayRun ? Action.CONTINUE : Action.NONE;
    }

    /**
     * Why the network may not be used now, or null when it may. The mobile data rule is the start
     * gate's own rule (NetworkPolicy), so the start and the in-flight check cannot disagree.
     */
    public static Reason reason(NetworkClass net, boolean meteredConsent, boolean onlineAllowed) {
        if (!onlineAllowed) return Reason.KEPT_OFFLINE;
        if (POLICY.decideHeavyStart(net, meteredConsent) == NetworkPolicyDecision.NEEDS_CONSENT) {
            return Reason.METERED_NO_CONSENT;
        }
        return null;
    }

    private static final NetworkPolicy POLICY = new NetworkPolicy();
}
