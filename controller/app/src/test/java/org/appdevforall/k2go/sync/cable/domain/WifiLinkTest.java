package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WifiLinkTest {

    @Test
    public void bandFromFrequency() {
        assertEquals(WifiLink.Band.GHZ_5, WifiLink.band(5745));
        assertEquals(WifiLink.Band.GHZ_2_4, WifiLink.band(2437));
        assertEquals(WifiLink.Band.NONE, WifiLink.band(-1));
    }

    @Test
    public void greenOnlyWithSenderAnd5Ghz() {
        assertEquals(WifiLink.Level.OK, WifiLink.judge(true, true, WifiLink.Band.GHZ_5));
        assertEquals(WifiLink.Level.WARN, WifiLink.judge(true, true, WifiLink.Band.GHZ_2_4));
        assertEquals(WifiLink.Level.WARN, WifiLink.judge(true, false, WifiLink.Band.GHZ_5));
        assertEquals(WifiLink.Level.OFF, WifiLink.judge(false, true, WifiLink.Band.GHZ_5));
    }
}
