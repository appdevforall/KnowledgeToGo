package org.appdevforall.k2go.networkpolicy.domain;

/**
 * The single rule for "may a heavy transfer start now?". Pure, no Android.
 *
 * <p>Defensive by design (see ADR-395): the gate acts at the START of a new
 * transfer. It does not micro-manage a transfer already in flight -- once bytes
 * move on a link the app does not own (the in-proot server pulls content over
 * the device default network), Android gives no fine control. So the contract
 * is simple: do not START anything costly without consent.
 */
public final class NetworkPolicy {

    /**
     * @param net       cost class of the active default network
     * @param consented the user granted "spend metered data" for this session
     */
    public NetworkPolicyDecision decideHeavyStart(NetworkClass net, boolean consented) {
        switch (net) {
            case UNMETERED:
                return NetworkPolicyDecision.ALLOW;
            case METERED:
                return consented ? NetworkPolicyDecision.ALLOW : NetworkPolicyDecision.NEEDS_CONSENT;
            case NONE:
            default:
                return NetworkPolicyDecision.BLOCKED_NO_NETWORK;
        }
    }
}
