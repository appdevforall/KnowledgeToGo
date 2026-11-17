package org.appdevforall.k2go.hotspot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-577: the one Hotspot / Wi-Fi rule shared by Connect, Clone and Share. */
public class HotspotModeTest {

    private static final class FakePort implements HotspotMode.Port {
        boolean supported = true;
        boolean permitted = true;
        boolean on;
        int acquires;
        int releases;
        boolean dropsClients;

        @Override public boolean supported() { return supported; }
        @Override public boolean permitted() { return permitted; }
        @Override public boolean isOn() { return on; }
        @Override public void acquire() { acquires++; on = true; }
        @Override public void release() { releases++; }
        @Override public boolean releaseDropsClients() { return dropsClients; }
    }

    @Test
    public void hotspotHoldsAndWifiReleases() {
        FakePort port = new FakePort();
        HotspotMode m = new HotspotMode(port, HotspotMode.Mode.HOTSPOT);
        assertFalse(m.choose(HotspotMode.Mode.HOTSPOT));
        assertEquals(1, port.acquires);
        assertFalse(m.choose(HotspotMode.Mode.WIFI));
        assertEquals(1, port.releases);   // K2GO-561: a switch to Wi-Fi lets the hotspot go
        assertFalse(m.isHotspot());
    }

    @Test
    public void missingPermissionIsAskedOncePerChoice() {
        FakePort port = new FakePort();
        port.permitted = false;
        HotspotMode m = new HotspotMode(port, HotspotMode.Mode.HOTSPOT);
        assertTrue(m.choose(HotspotMode.Mode.HOTSPOT));
        assertFalse(m.ensure());          // a re-render does not ask again
        assertFalse(m.ensure());
        m.choose(HotspotMode.Mode.WIFI);
        assertTrue(m.choose(HotspotMode.Mode.HOTSPOT));   // a new choice may ask once more
        assertEquals(0, port.acquires);
    }

    @Test
    public void resumeNeverAsksAndHoldsOnceGranted() {
        FakePort port = new FakePort();
        port.permitted = false;
        HotspotMode m = new HotspotMode(port, HotspotMode.Mode.HOTSPOT);
        m.resume();
        assertEquals(0, port.acquires);
        port.permitted = true;            // granted in the dialog or in Settings
        m.resume();
        assertEquals(1, port.acquires);
    }

    @Test
    public void unsupportedPhoneNeverAsksNorHolds() {
        FakePort port = new FakePort();
        port.supported = false;
        HotspotMode m = new HotspotMode(port, HotspotMode.Mode.HOTSPOT);
        assertFalse(m.choose(HotspotMode.Mode.HOTSPOT));
        assertEquals(0, port.acquires);
    }

    @Test
    public void onlyLeavingHotspotWithClientsWarns() {
        FakePort port = new FakePort();
        HotspotMode m = new HotspotMode(port, HotspotMode.Mode.HOTSPOT);
        assertFalse(m.switchDropsClients(HotspotMode.Mode.WIFI));   // nobody joined, or another holder
        port.dropsClients = true;
        assertTrue(m.switchDropsClients(HotspotMode.Mode.WIFI));
        assertFalse(m.switchDropsClients(HotspotMode.Mode.HOTSPOT));
        m.choose(HotspotMode.Mode.WIFI);
        assertFalse(m.switchDropsClients(HotspotMode.Mode.WIFI));
    }

    @Test
    public void releaseGivesBackTheHold() {
        FakePort port = new FakePort();
        HotspotMode m = new HotspotMode(port, HotspotMode.Mode.HOTSPOT);
        m.choose(HotspotMode.Mode.HOTSPOT);
        m.release();
        assertEquals(1, port.releases);
    }
}
