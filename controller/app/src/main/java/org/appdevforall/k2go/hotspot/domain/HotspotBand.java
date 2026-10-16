/*
 * ============================================================================
 * Name        : HotspotBand.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-127. The hotspot's Wi-Fi band choice. One source for the band set, their
 *               stable persisted ids, and their order. The presentation layer maps each to a label
 *               and the Wi-Fi Direct layer maps each to WifiP2pConfig.GROUP_OWNER_BAND_*; neither
 *               re-lists the choices. Pure JVM (no Android). 2.4 GHz is the default: it reaches
 *               more and older devices.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

public enum HotspotBand {
    TWO_GHZ(0),
    FIVE_GHZ(1),
    AUTO(2);

    /** Stable id persisted in storage. Do not renumber existing values. */
    public final int id;

    HotspotBand(int id) { this.id = id; }

    public static final HotspotBand DEFAULT = TWO_GHZ;

    /** The band for a stored id, or {@link #DEFAULT} for an unknown/corrupt id. */
    public static HotspotBand fromId(int id) {
        for (HotspotBand b : values()) {
            if (b.id == id) return b;
        }
        return DEFAULT;
    }
}
