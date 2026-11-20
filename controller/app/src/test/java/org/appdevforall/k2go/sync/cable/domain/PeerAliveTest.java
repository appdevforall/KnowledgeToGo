package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PeerAliveTest {

    @Test
    public void silentPeerThatPingsIsDead() {
        assertTrue(PeerAlive.isDead(true, false, PeerAlive.DEAD_MS + 1));
    }

    @Test
    public void shortSilenceIsNotDead() {
        assertFalse(PeerAlive.isDead(true, false, PeerAlive.DEAD_MS));
    }

    @Test
    public void olderPeerWithoutPingIsNeverDead() {
        assertFalse(PeerAlive.isDead(false, false, 60_000));
    }

    @Test
    public void localBackpressureIsNotADeadPeer() {
        assertFalse(PeerAlive.isDead(true, true, 60_000));
    }
}
