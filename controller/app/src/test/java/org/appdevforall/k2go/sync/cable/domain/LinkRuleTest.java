package org.appdevforall.k2go.sync.cable.domain;

import static org.appdevforall.k2go.sync.cable.domain.LinkRule.row;
import static org.junit.Assert.assertEquals;

import org.appdevforall.k2go.sync.cable.domain.LinkRule.Badge;
import org.appdevforall.k2go.sync.cable.domain.LinkRule.Hotspot;
import org.appdevforall.k2go.sync.cable.domain.LinkRule.Text;
import org.appdevforall.k2go.sync.cable.domain.LinkRule.Tone;
import org.appdevforall.k2go.sync.cable.domain.WifiLink.Band;
import org.junit.Test;

public class LinkRuleTest {

    // receive(wifiOn, onNetwork, paired, answers, viaHotspot, carrying, band, cableLinked)

    @Test
    public void receiverOnRouterBeforeCodeIsNeutral() {
        assertEquals(row(Tone.NEUTRAL, Text.WAITING_CODE, Badge.BAND),
                LinkRule.receive(true, true, false, false, true, false, Band.GHZ_5, false));
    }

    @Test
    public void receiverOnSenderHotspotIsGreenBeforeCode() {
        assertEquals(row(Tone.OK, Text.FOUND_HOTSPOT, Badge.BAND),
                LinkRule.receive(true, true, false, true, true, false, Band.GHZ_5, false));
    }

    @Test
    public void receiverPairedNamesThePath() {
        assertEquals(row(Tone.OK, Text.FOUND_NETWORK, Badge.BAND),
                LinkRule.receive(true, true, true, true, false, false, Band.GHZ_5, false));
        assertEquals(row(Tone.OK, Text.FOUND_HOTSPOT, Badge.BAND),
                LinkRule.receive(true, true, true, true, true, false, Band.GHZ_5, false));
    }

    @Test
    public void slowBandIsNotAWarning() {
        assertEquals(row(Tone.OK, Text.SLOW_BAND, Badge.BAND),
                LinkRule.receive(true, true, true, true, false, false, Band.GHZ_2_4, false));
    }

    @Test
    public void receiverAmberOnlyWhenNoOtherPath() {
        assertEquals(row(Tone.ACTION, Text.NOT_FOUND, Badge.NOT_READY),
                LinkRule.receive(true, true, true, false, false, false, Band.GHZ_5, false));
        assertEquals(row(Tone.NEUTRAL, Text.NOT_ON_NETWORK, Badge.NOT_USED),
                LinkRule.receive(true, true, true, false, false, false, Band.GHZ_5, true));
    }

    @Test
    public void wifiOffIsActionWithoutCableAndNeutralWithCable() {
        assertEquals(row(Tone.ACTION, Text.WIFI_OFF, Badge.OFF),
                LinkRule.receive(false, false, false, false, true, false, Band.NONE, false));
        assertEquals(row(Tone.NEUTRAL, Text.CABLE_ONLY, Badge.OFF),
                LinkRule.receive(false, false, true, false, false, false, Band.NONE, true));
    }

    @Test
    public void noNetwork() {
        assertEquals(row(Tone.ACTION, Text.NO_NETWORK, Badge.NOT_READY),
                LinkRule.receive(true, false, false, false, true, false, Band.NONE, false));
        assertEquals(row(Tone.NEUTRAL, Text.CABLE_ONLY, Badge.NOT_USED),
                LinkRule.receive(true, false, false, false, true, false, Band.NONE, true));
    }

    @Test
    public void carryingIsGreen() {
        assertEquals(row(Tone.OK, Text.CARRYING, Badge.BAND),
                LinkRule.receive(true, true, true, true, false, true, Band.GHZ_2_4, true));
    }

    // send(hotspotMode, hotspot, clients, ownBand, peerReaches, cableLinked)

    @Test
    public void senderHotspotStates() {
        assertEquals(row(Tone.NEUTRAL, Text.HOTSPOT_STARTING, Badge.STARTING),
                LinkRule.send(true, Hotspot.STARTING, 0, Band.NONE, null, false));
        assertEquals(row(Tone.NEUTRAL, Text.HOTSPOT_WAITING, Badge.READY),
                LinkRule.send(true, Hotspot.ON, 0, Band.NONE, null, false));
        assertEquals(row(Tone.NEUTRAL, Text.HOTSPOT_ON, Badge.READY),
                LinkRule.send(true, Hotspot.ON, -1, Band.NONE, null, false));
        assertEquals(row(Tone.OK, Text.HOTSPOT_JOINED, Badge.LINKED),
                LinkRule.send(true, Hotspot.ON, 1, Band.NONE, null, false));
        assertEquals(row(Tone.OK, Text.HOTSPOT_ON, Badge.LINKED),
                LinkRule.send(true, Hotspot.ON, -1, Band.NONE, true, true));
        assertEquals(row(Tone.ACTION, Text.HOTSPOT_OFF, Badge.NOT_READY),
                LinkRule.send(true, Hotspot.OFF, 0, Band.NONE, null, false));
        assertEquals(row(Tone.NEUTRAL, Text.CABLE_ONLY, Badge.NOT_USED),
                LinkRule.send(true, Hotspot.OFF, 0, Band.NONE, null, true));
    }

    @Test
    public void senderRouterWaitsUntilTheReceiverConfirms() {
        assertEquals(row(Tone.NEUTRAL, Text.NETWORK_WAITING, Badge.BAND),
                LinkRule.send(false, Hotspot.OFF, 0, Band.GHZ_5, null, false));
        assertEquals(row(Tone.OK, Text.NETWORK_SHARING, Badge.BAND),
                LinkRule.send(false, Hotspot.OFF, 0, Band.GHZ_2_4, true, true));
        assertEquals(row(Tone.ACTION, Text.NO_NETWORK, Badge.NOT_READY),
                LinkRule.send(false, Hotspot.OFF, 0, Band.NONE, null, false));
    }

    @Test
    public void senderPeerNotOnWifiIsNotUsed() {
        assertEquals(row(Tone.NEUTRAL, Text.NOT_ON_NETWORK, Badge.NOT_USED),
                LinkRule.send(true, Hotspot.ON, 0, Band.GHZ_5, false, true));
        assertEquals(row(Tone.NEUTRAL, Text.NOT_ON_NETWORK, Badge.NOT_USED),
                LinkRule.send(false, Hotspot.OFF, 0, Band.GHZ_5, false, true));
    }

    // cable(linked, waiting, carrying, done)

    @Test
    public void cableRow() {
        assertEquals(row(Tone.NEUTRAL, Text.CABLE_NONE, Badge.OPTIONAL), LinkRule.cable(false, false, false, false));
        assertEquals(row(Tone.ACTION, Text.CABLE_WAITING, Badge.WAITING), LinkRule.cable(false, true, false, false));
        assertEquals(row(Tone.OK, Text.CABLE_LINKED, Badge.LINKED), LinkRule.cable(true, false, false, false));
        assertEquals(row(Tone.OK, Text.CARRYING, Badge.LINKED), LinkRule.cable(true, false, true, false));
    }

    @Test
    public void doneWinsOverTheLinkState() {
        assertEquals(row(Tone.NEUTRAL, Text.CABLE_DONE, Badge.DONE), LinkRule.cable(false, false, false, true));
        assertEquals(row(Tone.NEUTRAL, Text.CABLE_DONE, Badge.DONE), LinkRule.cable(true, false, false, true));
        assertEquals(row(Tone.OK, Text.CARRYING, Badge.LINKED), LinkRule.cable(true, false, true, true));
    }
}
