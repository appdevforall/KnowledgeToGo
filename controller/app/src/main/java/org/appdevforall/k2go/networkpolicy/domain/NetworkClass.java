package org.appdevforall.k2go.networkpolicy.domain;

/**
 * Cost class of the ACTIVE DEFAULT network, from the app point of view.
 *
 * <p>The split is by metered state, not by transport. On real hardware a carrier
 * runs several cellular data networks at once: the IMS signaling network reports
 * NOT_METERED, while the general-internet APN does not. Keying on
 * TRANSPORT_CELLULAR would therefore misjudge cost. The one reliable signal is
 * the active default network NET_CAPABILITY_NOT_METERED. See
 * ADR-395 (device evidence appendix) for the measured values.
 */
public enum NetworkClass {

    /** Has internet and is not metered (home Wi-Fi, unmetered ethernet). Free to use. */
    UNMETERED,

    /** Has internet but is metered (cellular internet APN, a metered Wi-Fi hotspot). Costs data. */
    METERED,

    /** No internet-capable default network. Nothing can be downloaded. */
    NONE;

    /**
     * Pure mapping from the two facts the data layer reads off the active default
     * network. Kept here so the rule is unit-tested without Android.
     *
     * @param hasInternet the default network has NET_CAPABILITY_INTERNET
     * @param notMetered  the default network has NET_CAPABILITY_NOT_METERED
     */
    public static NetworkClass from(boolean hasInternet, boolean notMetered) {
        if (!hasInternet) return NONE;
        return notMetered ? UNMETERED : METERED;
    }
}
