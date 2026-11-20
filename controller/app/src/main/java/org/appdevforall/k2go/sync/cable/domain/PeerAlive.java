/*
 * ============================================================================
 * Name        : PeerAlive.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : When a quiet cable means the other phone is gone (K2GO-584).
 *               Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class PeerAlive {

    /** Each side sends PING this often (K2GO-587). */
    public static final long PING_MS = 1_000;

    /** Three missed PINGs mean the peer is gone. */
    public static final long DEAD_MS = 3 * PING_MS;

    private PeerAlive() {
    }

    /**
     * @param peerPings the peer sent a PING, so it runs the keepalive. An older K2Go never sends
     *                  PING: its silence is normal and never closes the link.
     * @param busy      our read thread waits on a local socket (slow storage), not on the cable
     * @param quietMs   time since bytes last came in on the cable, or since the read thread was
     *                  last busy
     */
    public static boolean isDead(boolean peerPings, boolean busy, long quietMs) {
        return peerPings && !busy && quietMs > DEAD_MS;
    }
}
