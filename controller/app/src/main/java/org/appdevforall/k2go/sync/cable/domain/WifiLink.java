/*
 * ============================================================================
 * Name        : WifiLink.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The rule for the Wi-Fi row of the Clone screen (K2GO-576):
 *               green only when the receiver is on the sender's network, the
 *               sender answers, and the band is 5 GHz. 2.4 GHz is about 5 times
 *               slower, so it is a warning even when the sender answers. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class WifiLink {

    public enum Level { OK, WARN, OFF }

    public enum Band { NONE, GHZ_2_4, GHZ_5 }

    private WifiLink() {
    }

    public static Band band(int frequencyMhz) {
        if (frequencyMhz >= 4900) return Band.GHZ_5;
        if (frequencyMhz >= 2400) return Band.GHZ_2_4;
        return Band.NONE;
    }

    public static Level judge(boolean wifiOn, boolean senderAnswers, Band band) {
        if (!wifiOn) return Level.OFF;
        if (!senderAnswers || band != Band.GHZ_5) return Level.WARN;
        return Level.OK;
    }
}
