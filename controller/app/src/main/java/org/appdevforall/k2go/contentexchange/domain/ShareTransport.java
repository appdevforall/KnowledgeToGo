/*
 * ============================================================================
 * Name        : ShareTransport.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. One source for the content-exchange wireless-share transport
 *               constants: the off-loopback port the receiver reaches the sender on, the
 *               scheme, and the QR application tag. Pure (no android.*); the sender and the
 *               later receiver fork both read it so the two forks cannot drift apart.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

/**
 * Transport settings for granular wireless Share (K2GO-376).
 *
 * <p>Share is the network twin of Export: the receiver PULLS from the sender's live conex-core
 * (ADR "Pillar Share"). On the sender the share session is created over the loopback relay, but
 * the RECEIVER reaches the gate over the hotspot at {@code http://<senderHotspotIp>:SHARE_PORT}.
 * That off-loopback exposure is a separate server-side slice (plain HTTP, v1 only); this is the
 * single constant the app points at it, so it stays in step with the exposure port.
 */
public final class ShareTransport {

    private ShareTransport() {
    }

    /**
     * TCP port the sender's conex-core is exposed on, off loopback, for the paired receiver. Must
     * match the scoped-exposure slice (candidate 8095, plain HTTP for v1). The loopback relay the
     * sender uses to CREATE the session is unrelated to this port.
     */
    public static final int SHARE_PORT = 8095;

    /** The receiver reaches the gate over plain HTTP on the hotspot (no TLS for v1; see the ADR). */
    public static final String SCHEME = "http";

    /** QR payload {@code app} tag, so a scanned code is recognised as a Share invite (not a Sync QR). */
    public static final String QR_APP_TAG = "iiab_share";
}
