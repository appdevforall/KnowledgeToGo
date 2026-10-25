/*
 * ============================================================================
 * Name        : ShareInviteValidatorTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. JVM unit tests for the Share invite rule (host/port/token/manifest path).
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShareInviteValidatorTest {

    private static final String HOST = "192.168.49.1";
    private static final int PORT = 8095;
    // url-safe base64, the shape of secrets.token_urlsafe(32).
    private static final String TOKEN = "n5Xk3_Qq-7bGf2hJkLmNoPqRsTuVwXyZ0123456789AB";
    private static final String MANIFEST = "/v1/share/abc123/manifest";

    @Test
    public void acceptsAWellFormedInvite() {
        assertTrue(ShareInviteValidator.validate(HOST, PORT, TOKEN, MANIFEST).valid);
    }

    @Test
    public void rejectsABadHost() {
        assertFalse(ShareInviteValidator.validate("10.0.0.1/evil", PORT, TOKEN, MANIFEST).valid);
        assertFalse(ShareInviteValidator.validate("user@host", PORT, TOKEN, MANIFEST).valid);
        assertFalse(ShareInviteValidator.validate("", PORT, TOKEN, MANIFEST).valid);
    }

    @Test
    public void rejectsABadPort() {
        assertFalse(ShareInviteValidator.validate(HOST, 0, TOKEN, MANIFEST).valid);
        assertFalse(ShareInviteValidator.validate(HOST, 70000, TOKEN, MANIFEST).valid);
        assertFalse(ShareInviteValidator.validate(HOST, -1, TOKEN, MANIFEST).valid);
    }

    @Test
    public void acceptsAUrlSafeToken() {
        assertTrue(ShareInviteValidator.isValidToken("ABCdef012-_"));
        assertTrue(ShareInviteValidator.isValidToken(TOKEN));
    }

    @Test
    public void rejectsABadToken() {
        assertFalse(ShareInviteValidator.isValidToken(null));
        assertFalse(ShareInviteValidator.isValidToken(""));
        assertFalse(ShareInviteValidator.isValidToken("has space"));
        assertFalse(ShareInviteValidator.isValidToken("has.dot"));
        assertFalse(ShareInviteValidator.isValidToken("new\nline"));
    }

    @Test
    public void acceptsAServerRelativeManifestPath() {
        assertTrue(ShareInviteValidator.isValidManifestPath("/v1/share/x/manifest"));
    }

    @Test
    public void rejectsABadManifestPath() {
        assertFalse(ShareInviteValidator.isValidManifestPath(null));
        assertFalse(ShareInviteValidator.isValidManifestPath(""));
        assertFalse(ShareInviteValidator.isValidManifestPath("v1/share/x"));        // no leading slash
        assertFalse(ShareInviteValidator.isValidManifestPath("//evil.example"));     // authority
        assertFalse(ShareInviteValidator.isValidManifestPath("/a/../../etc"));        // climbs out
        assertFalse(ShareInviteValidator.isValidManifestPath("http://evil/x"));       // absolute url
        assertFalse(ShareInviteValidator.isValidManifestPath("/has space"));          // whitespace
    }
}
