package org.appdevforall.k2go.networkpolicy.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure-JVM tests for the cost-class mapping. The cases mirror the device
 * evidence in ADR-395: Wi-Fi "HIKVISION" carried NOT_METERED; the "Bienestar"
 * LTE internet APN did not; the cellular IMS PDN carried NOT_METERED but no
 * INTERNET, so it never becomes the internet-bearing default that gets classified.
 */
public class NetworkClassTest {

    @Test
    public void wifiUnmetered_isUnmetered() {
        assertEquals(NetworkClass.UNMETERED, NetworkClass.from(true, true));
    }

    @Test
    public void cellularInternetApn_isMetered() {
        assertEquals(NetworkClass.METERED, NetworkClass.from(true, false));
    }

    @Test
    public void noInternet_isNone_whateverTheMeteredFlag() {
        assertEquals(NetworkClass.NONE, NetworkClass.from(false, false));
        assertEquals(NetworkClass.NONE, NetworkClass.from(false, true));
    }
}
