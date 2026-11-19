/*
 * ============================================================================
 * Name        : LinkRule.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : What the two Clone link rows (Wi-Fi, USB cable) show, on both
 *               phones (K2GO-584). OK: the path is verified or carries data.
 *               NEUTRAL: waiting, not used, optional or done. ACTION: the user
 *               must do something and no other path can carry the copy.
 *               Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

import java.util.Objects;

public final class LinkRule {

    public enum Tone { OK, NEUTRAL, ACTION }

    public enum Text {
        // Wi-Fi row, both phones
        WIFI_OFF, NO_NETWORK, CABLE_ONLY, NOT_ON_NETWORK, CARRYING,
        // Wi-Fi row, receiver
        WAITING_CODE, FOUND_HOTSPOT, FOUND_NETWORK, SLOW_BAND, NOT_FOUND, NOT_CHECKED,
        // Wi-Fi row, sender
        HOTSPOT_STARTING, HOTSPOT_OFF, HOTSPOT_ON, HOTSPOT_WAITING, HOTSPOT_JOINED,
        NETWORK_WAITING, NETWORK_SHARING, PEER_ON_WIFI, PEER_DONE_WIFI,
        // USB cable row
        CABLE_NONE, CABLE_PRESENT, CABLE_UNSUPPORTED, CABLE_SWITCHING, CABLE_ASK, CABLE_DENIED, CABLE_PEER,
        CABLE_LINKED, CABLE_DONE
    }

    public enum Badge { BAND, OFF, NOT_READY, NOT_USED, STARTING, READY, LINKED, WAITING, OPTIONAL, DONE }

    /** The sender's own hotspot. */
    public enum Hotspot { OFF, STARTING, ON }

    public static final class Row {
        public final Tone tone;
        public final Text text;
        public final Badge badge;

        Row(Tone tone, Text text, Badge badge) {
            this.tone = tone;
            this.text = text;
            this.badge = badge;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Row)) return false;
            Row r = (Row) o;
            return tone == r.tone && text == r.text && badge == r.badge;
        }

        @Override
        public int hashCode() {
            return Objects.hash(tone, text, badge);
        }

        @Override
        public String toString() {
            return tone + "/" + text + "/" + badge;
        }
    }

    private LinkRule() {
    }

    static Row row(Tone tone, Text text, Badge badge) {
        return new Row(tone, text, badge);
    }

    /**
     * Receiver Wi-Fi row. Before pairing only the sender's hotspot can answer (it is the gateway),
     * so a silent gateway means "not known yet", not "wrong network".
     *
     * @param onNetwork  the phone has a Wi-Fi network (a DHCP gateway)
     * @param viaHotspot the sender is the gateway (its hotspot), or nothing is paired yet
     * @param checked    the last check tried the paired sender's address (false: not tried yet)
     */
    public static Row receive(boolean wifiOn, boolean onNetwork, boolean paired, boolean answers,
                              boolean viaHotspot, boolean carrying, WifiLink.Band band,
                              boolean cableLinked, boolean checked) {
        if (!wifiOn) {
            return cableLinked ? row(Tone.NEUTRAL, Text.CABLE_ONLY, Badge.OFF)
                    : row(Tone.ACTION, Text.WIFI_OFF, Badge.OFF);
        }
        if (!onNetwork) {
            return cableLinked ? row(Tone.NEUTRAL, Text.CABLE_ONLY, Badge.NOT_USED)
                    : row(Tone.ACTION, Text.NO_NETWORK, Badge.NOT_READY);
        }
        if (carrying) return row(Tone.OK, Text.CARRYING, Badge.BAND);
        if (answers) {
            if (band == WifiLink.Band.GHZ_2_4) return row(Tone.OK, Text.SLOW_BAND, Badge.BAND);
            return row(Tone.OK, viaHotspot ? Text.FOUND_HOTSPOT : Text.FOUND_NETWORK, Badge.BAND);
        }
        if (!paired) return row(Tone.NEUTRAL, Text.WAITING_CODE, Badge.BAND);
        // A silent sender is known only after a check of its address: no claim before that.
        if (!checked) return row(Tone.NEUTRAL, Text.NOT_CHECKED, Badge.WAITING);
        return cableLinked ? row(Tone.NEUTRAL, Text.NOT_ON_NETWORK, Badge.NOT_USED)
                : row(Tone.ACTION, Text.NOT_FOUND, Badge.NOT_READY);
    }

    /**
     * Sender Wi-Fi row.
     *
     * @param clients     phones joined to the hotspot, or a negative value when unknown
     * @param ownBand     this phone's Wi-Fi band, NONE when it is not on a network
     * @param peerReaches the receiver's verdict over the cable, null when unknown
     * @param wifiPeer    what this phone's own rsync daemon saw from a receiver on Wi-Fi
     */
    public static Row send(boolean hotspotMode, Hotspot hotspot, int clients, WifiLink.Band ownBand,
                           Boolean peerReaches, boolean cableLinked, ShareSessions.WifiPeer wifiPeer) {
        // The daemon saw the receiver on Wi-Fi: direct proof, it wins over any other input.
        if (wifiPeer == ShareSessions.WifiPeer.CONNECTED) {
            return row(Tone.OK, Text.PEER_ON_WIFI, hotspotMode ? Badge.LINKED : Badge.BAND);
        }
        if (wifiPeer == ShareSessions.WifiPeer.ENDED) return row(Tone.NEUTRAL, Text.PEER_DONE_WIFI, Badge.DONE);
        // The verdict travels only on the cable, so a "no" means the cable carries the copy.
        if (Boolean.FALSE.equals(peerReaches)) return row(Tone.NEUTRAL, Text.NOT_ON_NETWORK, Badge.NOT_USED);
        boolean peerFound = Boolean.TRUE.equals(peerReaches);
        if (hotspotMode) {
            if (hotspot == Hotspot.STARTING) return row(Tone.NEUTRAL, Text.HOTSPOT_STARTING, Badge.STARTING);
            if (hotspot != Hotspot.ON) {
                return cableLinked ? row(Tone.NEUTRAL, Text.CABLE_ONLY, Badge.NOT_USED)
                        : row(Tone.ACTION, Text.HOTSPOT_OFF, Badge.NOT_READY);
            }
            if (clients > 0) return row(Tone.OK, Text.HOTSPOT_JOINED, Badge.LINKED);
            if (peerFound) return row(Tone.OK, Text.HOTSPOT_ON, Badge.LINKED);
            return row(Tone.NEUTRAL, clients == 0 ? Text.HOTSPOT_WAITING : Text.HOTSPOT_ON, Badge.READY);
        }
        if (ownBand == WifiLink.Band.NONE) {
            return cableLinked ? row(Tone.NEUTRAL, Text.CABLE_ONLY, Badge.NOT_USED)
                    : row(Tone.ACTION, Text.NO_NETWORK, Badge.NOT_READY);
        }
        return peerFound ? row(Tone.OK, Text.NETWORK_SHARING, Badge.BAND)
                : row(Tone.NEUTRAL, Text.NETWORK_WAITING, Badge.BAND);
    }

    /** The cable link, as the session sees it on this phone. */
    public enum Cable {
        /** No Clone cable link (a plain USB cable may still be plugged in). */
        NONE,
        /** Our phone switched the other phone to accessory mode; it reconnects in a moment. */
        SWITCHING,
        /** A USB dialog on this phone waits for the user. */
        ASK_HERE,
        /** The user refused USB access; it is asked again only after a replug. */
        DENIED,
        /** The cable is open, but the other phone does not answer (Clone is not ready on it). */
        PEER,
        /** The device on the cable has no accessory mode (an iPhone, for example): Clone cannot use it. */
        UNSUPPORTED,
        LINKED
    }

    /**
     * USB cable row, both phones. {@code done}: a copy finished while the cable was linked. Data on
     * the cable after that is a new copy (a second phone on the same share), so it wins.
     * {@code present}: a USB data cable is plugged in.
     */
    public static Row cable(Cable link, boolean carrying, boolean done, boolean present) {
        boolean linked = link == Cable.LINKED;
        if (done && !(linked && carrying)) return row(Tone.NEUTRAL, Text.CABLE_DONE, Badge.DONE);
        switch (link) {
            case LINKED: return row(Tone.OK, carrying ? Text.CARRYING : Text.CABLE_LINKED, Badge.LINKED);
            case ASK_HERE: return row(Tone.ACTION, Text.CABLE_ASK, Badge.WAITING);
            case DENIED: return row(Tone.ACTION, Text.CABLE_DENIED, Badge.NOT_READY);
            case PEER: return row(Tone.ACTION, Text.CABLE_PEER, Badge.WAITING);
            case SWITCHING: return row(Tone.NEUTRAL, Text.CABLE_SWITCHING, Badge.WAITING);
            case UNSUPPORTED: return row(Tone.NEUTRAL, Text.CABLE_UNSUPPORTED, Badge.NOT_USED);
            default:
                // A phone that is only the USB device cannot tell a computer from a phone at the
                // other end: only the other phone starts the link. So no promise and no "Waiting".
                return present ? row(Tone.NEUTRAL, Text.CABLE_PRESENT, Badge.OPTIONAL)
                        : row(Tone.NEUTRAL, Text.CABLE_NONE, Badge.OPTIONAL);
        }
    }
}
