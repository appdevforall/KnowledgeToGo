/*
 * ============================================================================
 * Name        : WifiLink.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The Wi-Fi band of the Clone link (K2GO-576). 2.4 GHz is about
 *               5 times slower than 5 GHz. The row colours are in LinkRule.
 *               Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class WifiLink {

    public enum Band { NONE, GHZ_2_4, GHZ_5 }

    private WifiLink() {
    }

    public static Band band(int frequencyMhz) {
        if (frequencyMhz >= 4900) return Band.GHZ_5;
        if (frequencyMhz >= 2400) return Band.GHZ_2_4;
        return Band.NONE;
    }
}
