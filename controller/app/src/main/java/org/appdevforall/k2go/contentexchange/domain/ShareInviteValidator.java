/*
 * ============================================================================
 * Name        : ShareInviteValidator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. Domain rule: what counts as a *safe* Share invite (the QR payload the
 *               sender shows and the receiver scans). Pure (no android.*, no JSON); the single
 *               source for the invite shape, so the sender and the receiver fork validate the
 *               same thing. Reuses the host/port rule from the sync security slice (S1).
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

import org.appdevforall.k2go.sync.domain.SyncCredentialValidator;

/**
 * Pure validation of a Share invite (ADR "Pillar Share"). The invite carries the sender's reachable
 * address and port, a session token, and the manifest path. On the sender these come from trusted
 * local sources (the hotspot owner and the sender's own conex-core), but the RECEIVER scans the same
 * QR as untrusted input, and both forks build against this one rule so the contract cannot drift.
 *
 * <p>The receiver later composes URLs from these fields ({@code http://host:port} + a share path),
 * so the host/port rule is reused from {@link SyncCredentialValidator} (the hardened S1 rule) and
 * the token/path are held to conservative, injection-free character sets.
 */
public final class ShareInviteValidator {

    /** The conex-core token is {@code secrets.token_urlsafe(32)}: url-safe base64, ~43 chars. */
    private static final int MAX_TOKEN_LEN = 256;
    /** The manifest path is short ({@code /v1/share/<id>/manifest}); cap it well above that. */
    private static final int MAX_PATH_LEN = 512;

    private ShareInviteValidator() {
    }

    /** Validate every field of an invite. Call this at the build boundary (sender) and the scan
     *  boundary (receiver); reject the whole invite when it returns invalid. */
    public static SyncCredentialValidator.Result validate(String host, int port, String token,
                                                          String manifestUrl) {
        if (!SyncCredentialValidator.isValidHost(host)) {
            return SyncCredentialValidator.Result.fail("invalid host");
        }
        if (!SyncCredentialValidator.isValidPort(port)) {
            return SyncCredentialValidator.Result.fail("invalid port");
        }
        if (!isValidToken(token)) {
            return SyncCredentialValidator.Result.fail("invalid token");
        }
        if (!isValidManifestPath(manifestUrl)) {
            return SyncCredentialValidator.Result.fail("invalid manifest path");
        }
        return SyncCredentialValidator.Result.ok();
    }

    /**
     * A token safe to place in an HTTP header and compare: a non-empty run of url-safe base64
     * characters ({@code [A-Za-z0-9-_]}), which is exactly what {@code token_urlsafe} produces. No
     * whitespace or control characters, so it cannot smuggle a second header line.
     */
    public static boolean isValidToken(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LEN) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * A server-relative path safe to append to {@code http://host:port}: it must start with a single
     * {@code /}, carry no whitespace or control characters, no {@code ..} segment, and no scheme or
     * authority ({@code ://}), so it cannot redirect the receiver to another host.
     */
    public static boolean isValidManifestPath(String path) {
        if (path == null || path.length() < 2 || path.length() > MAX_PATH_LEN) {
            return false;
        }
        if (path.charAt(0) != '/' || path.charAt(1) == '/') {
            return false;   // must be a single-slash absolute path, not "//host" (an authority)
        }
        if (path.contains("..") || path.contains("://")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return false;   // no control characters, space or DEL
            }
        }
        return true;
    }
}
