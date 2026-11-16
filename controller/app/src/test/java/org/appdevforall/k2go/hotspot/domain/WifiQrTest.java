package org.appdevforall.k2go.hotspot.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WifiQrTest {

    @Test
    public void wpaNetwork() {
        assertEquals("WIFI:T:WPA;S:DIRECT-K2GO-ab;P:KnowledgeToGo;;",
                WifiQr.payload("DIRECT-K2GO-ab", "KnowledgeToGo"));
    }

    @Test
    public void openNetworkWhenNoPassword() {
        assertEquals("WIFI:T:nopass;S:Cafe;;", WifiQr.payload("Cafe", ""));
        assertEquals("WIFI:T:nopass;S:Cafe;;", WifiQr.payload("Cafe", null));
    }

    @Test
    public void reservedCharactersAreEscaped() {
        assertEquals("WIFI:T:WPA;S:a\\;b\\:c;P:p\\,q\\\"r\\\\;;", WifiQr.payload("a;b:c", "p,q\"r\\"));
    }
}
