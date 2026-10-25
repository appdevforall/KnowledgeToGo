/*
 * ============================================================================
 * Name        : ShareHandshakeTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. JVM unit tests for the Share QR schema (encode + parse round-trip + the
 *               fail-closed rejections). Uses the real org.json on the test classpath.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class ShareHandshakeTest {

    private static final String HOST = "192.168.49.1";
    private static final int PORT = 8095;
    private static final String TOKEN = "n5Xk3_Qq-7bGf2hJkLmNoPqRsTuVwXyZ0123456789AB";
    private static final String MANIFEST = "/v1/share/abc123/manifest";

    @Test
    public void encodeThenDecodeRoundTrips() {
        String payload = ShareHandshake.encode(HOST, PORT, TOKEN, MANIFEST);
        assertNotNull(payload);
        ShareHandshake.ShareInvite invite = ShareHandshake.decode(payload);
        assertNotNull(invite);
        assertEquals(HOST, invite.host);
        assertEquals(PORT, invite.port);
        assertEquals(TOKEN, invite.token);
        assertEquals(MANIFEST, invite.manifestUrl);
        assertEquals("http://192.168.49.1:8095", invite.baseUrl());
    }

    @Test
    public void encodeRejectsAnInvalidInvite() {
        assertNull(ShareHandshake.encode("10.0.0.1/evil", PORT, TOKEN, MANIFEST));
        assertNull(ShareHandshake.encode(HOST, 0, TOKEN, MANIFEST));
        assertNull(ShareHandshake.encode(HOST, PORT, "bad token", MANIFEST));
        assertNull(ShareHandshake.encode(HOST, PORT, TOKEN, "no-leading-slash"));
    }

    @Test
    public void decodeRejectsAForeignAppTag() {
        // A sync QR, not a share invite.
        assertNull(ShareHandshake.decode("{\"app\":\"iiab_sync\",\"ip\":\"192.168.49.1\",\"port\":8730}"));
    }

    @Test
    public void decodeRejectsAMalformedAddress() {
        assertNull(ShareHandshake.decode(
                "{\"app\":\"iiab_share\",\"addr\":\"192.168.49.1\",\"token\":\"" + TOKEN
                        + "\",\"manifestUrl\":\"" + MANIFEST + "\"}"));   // no :port
    }

    @Test
    public void decodeRejectsAnInjectedToken() {
        assertNull(ShareHandshake.decode(
                "{\"app\":\"iiab_share\",\"addr\":\"192.168.49.1:8095\",\"token\":\"bad token\",\"manifestUrl\":\""
                        + MANIFEST + "\"}"));
    }

    @Test
    public void decodeRejectsGarbage() {
        assertNull(ShareHandshake.decode("not json"));
        assertNull(ShareHandshake.decode("{}"));
    }
}
