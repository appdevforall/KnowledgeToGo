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
}
