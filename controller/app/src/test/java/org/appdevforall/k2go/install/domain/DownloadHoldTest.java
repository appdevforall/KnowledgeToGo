/*
 * ============================================================================
 * Name        : DownloadHoldTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-593. Unit tests for the hold of a module download on
 *               mobile data and with Keep offline.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.appdevforall.k2go.install.domain.DownloadHold.Action;
import org.appdevforall.k2go.install.domain.DownloadHold.Reason;
import org.appdevforall.k2go.install.domain.DownloadHold.StoppedBy;
import org.appdevforall.k2go.networkpolicy.domain.NetworkClass;
import org.junit.Test;

public class DownloadHoldTest {

    /** The A16 case: Wi-Fi drops, the phone moves to 4G, no consent: the running download holds. */
    @Test
    public void runningDownloadHoldsOnMobileDataWithoutConsent() {
        assertEquals(Action.HOLD, DownloadHold.decide(NetworkClass.METERED, false, true, StoppedBy.NOBODY));
        assertEquals(Reason.METERED_NO_CONSENT, DownloadHold.reason(NetworkClass.METERED, false, true));
    }

    @Test
    public void runningDownloadContinuesOnMobileDataWithConsent() {
        assertEquals(Action.NONE, DownloadHold.decide(NetworkClass.METERED, true, true, StoppedBy.NOBODY));
        assertNull(DownloadHold.reason(NetworkClass.METERED, true, true));
    }

    @Test
    public void heldDownloadContinuesWhenWifiComesBack() {
        assertEquals(Action.CONTINUE, DownloadHold.decide(NetworkClass.UNMETERED, false, true, StoppedBy.NETWORK));
    }

    @Test
    public void heldDownloadContinuesAfterConsent() {
        assertEquals(Action.CONTINUE, DownloadHold.decide(NetworkClass.METERED, true, true, StoppedBy.NETWORK));
    }

    @Test
    public void heldDownloadStaysHeldWhileTheReasonIsTrue() {
        assertEquals(Action.NONE, DownloadHold.decide(NetworkClass.METERED, false, true, StoppedBy.NETWORK));
    }

    /** K2GO-588: Keep offline holds the download on any network, Wi-Fi too. */
    @Test
    public void keepOfflineHoldsOnWifi() {
        assertEquals(Action.HOLD, DownloadHold.decide(NetworkClass.UNMETERED, false, false, StoppedBy.NOBODY));
        assertEquals(Reason.KEPT_OFFLINE, DownloadHold.reason(NetworkClass.UNMETERED, true, false));
    }

    /** Keep offline is the first reason: allowing it can still leave the mobile data reason. */
    @Test
    public void keepOfflineComesBeforeMobileData() {
        assertEquals(Reason.KEPT_OFFLINE, DownloadHold.reason(NetworkClass.METERED, false, false));
        assertEquals(Reason.METERED_NO_CONSENT, DownloadHold.reason(NetworkClass.METERED, false, true));
    }

    /** A user pause is the user's: a network event never continues it and never re-holds it. */
    @Test
    public void userPauseIsNeverChangedByTheNetwork() {
        for (NetworkClass net : NetworkClass.values()) {
            for (boolean consent : new boolean[]{false, true}) {
                for (boolean online : new boolean[]{false, true}) {
                    assertEquals(Action.NONE, DownloadHold.decide(net, consent, online, StoppedBy.USER));
                }
            }
        }
    }

    /** No network is not a hold: the box reconnects by itself and there is no traffic to stop. */
    @Test
    public void noNetworkChangesNothing() {
        assertEquals(Action.NONE, DownloadHold.decide(NetworkClass.NONE, false, true, StoppedBy.NOBODY));
        assertEquals(Action.NONE, DownloadHold.decide(NetworkClass.NONE, false, true, StoppedBy.NETWORK));
    }

    @Test
    public void wifiWithOnlineAllowedRunsFree() {
        assertEquals(Action.NONE, DownloadHold.decide(NetworkClass.UNMETERED, false, true, StoppedBy.NOBODY));
        assertNull(DownloadHold.reason(NetworkClass.UNMETERED, false, true));
    }
}
