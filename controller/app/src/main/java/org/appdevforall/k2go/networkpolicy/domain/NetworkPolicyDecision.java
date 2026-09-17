package org.appdevforall.k2go.networkpolicy.domain;

/** What a caller must do before starting a heavy (costly) transfer. */
public enum NetworkPolicyDecision {

    /** Proceed now. The network is free, or the user already consented to spend data. */
    ALLOW,

    /** Ask the user to consent to spending metered data; proceed only on a yes. */
    NEEDS_CONSENT,

    /** No usable network. Do not start; tell the user they are offline. */
    BLOCKED_NO_NETWORK
}
