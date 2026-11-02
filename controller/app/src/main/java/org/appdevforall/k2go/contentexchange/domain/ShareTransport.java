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

import org.appdevforall.k2go.config.BoxEndpoints;

/**
 * Transport settings for granular wireless Share (K2GO-376).
 *
 * <p>Share is the network twin of Export: the receiver PULLS from the sender's live conex-core
 * (ADR "Pillar Share"). On the sender the share session is created over the loopback relay, but
 * the RECEIVER reaches the gate over the hotspot at
 * {@code http://<senderHotspotIp>:SHARE_PORT + SHARE_PATH_PREFIX + /v1/share/...}. The gate is the
 * conex_core role's own nginx conf on the box's main server (plain HTTP, v1 only).
 */
public final class ShareTransport {

    private ShareTransport() {
    }

    /**
     * TCP port the receiver reaches the sender's Share gate on: the box's main nginx server, where
     * the conex_core role installs the gate. The loopback relay the sender uses to CREATE the
     * session is a separate path.
     */
    public static final int SHARE_PORT = BoxEndpoints.PORT;

    /**
     * URL prefix of the Share gate on that server. Must match the conex_core role variable
     * {@code conex_core_url}. conex-core itself serves {@code /v1/share/...}; the gate maps
     * {@code SHARE_PATH_PREFIX + /v1/share/...} onto it.
     */
    public static final String SHARE_PATH_PREFIX = "/conex-core";

    /** The receiver reaches the gate over plain HTTP on the hotspot (no TLS for v1; see the ADR). */
    public static final String SCHEME = "http";

    /** QR payload {@code app} tag, so a scanned code is recognised as a Share invite (not a Sync QR). */
    public static final String QR_APP_TAG = "iiab_share";
}
