package org.appdevforall.k2go.networkpolicy.domain;

/** Pure rule for the proactive alert: warn when the default network becomes metered. */
public final class NetworkTransition {

    private NetworkTransition() {}

    /**
     * True when the default network just crossed INTO a metered state from a
     * non-metered one -- the moment to warn the user that further activity spends
     * data. A metered-to-metered change, or any change back to unmetered, never
     * warns.
     */
    public static boolean shouldWarn(NetworkClass previous, NetworkClass next) {
        return next == NetworkClass.METERED && previous != NetworkClass.METERED;
    }
}
