/*
 * ============================================================================
 * Name        : ShareHandshake.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. The Share QR payload schema (encode + parse), a variant of the sync
 *               handshake (SyncHandshakeHelper) for the content-exchange feature. Encode is the
 *               sender side (build the invite to render); parse is the receiver side; both go
 *               through ShareInviteValidator, so one class owns the invite contract for both forks.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.contentexchange.domain.ShareInviteValidator;
import org.appdevforall.k2go.contentexchange.domain.ShareTransport;
import org.appdevforall.k2go.sync.domain.SyncCredentialValidator;
import org.json.JSONObject;

/**
 * Encodes and parses the Share invite QR (ADR "Pillar Share"). The payload is small JSON:
 *
 * <pre>{"app":"iiab_share","addr":"&lt;host&gt;:&lt;port&gt;","token":"&lt;t&gt;","manifestUrl":"/v1/share/&lt;id&gt;/manifest"}</pre>
 *
 * <p>{@code addr} is the sender's hotspot IP and the share port joined with a colon (the one field
 * the ADR calls the address). The receiver composes {@code http://addr + manifestUrl} for the
 * manifest and {@code http://addr + /v1/share/<id>/fetch/<ref>} for each item, with the token in the
 * {@code X-Share-Token} header (never the URL). Parsing is fail-closed: an invite that does not
 * validate returns {@code null}, mirroring {@code SyncHandshakeHelper.parsePayload}.
 */
public final class ShareHandshake {

    private ShareHandshake() {
    }

    /** A parsed, validated Share invite: the host and port split out of {@code addr}, plus the token
     *  and manifest path. {@link #baseUrl()} is the origin the receiver prefixes to share paths. */
    public static final class ShareInvite {
        public final String host;
        public final int port;
        public final String token;
        public final String manifestUrl;

        public ShareInvite(String host, int port, String token, String manifestUrl) {
            this.host = host;
            this.port = port;
            this.token = token;
            this.manifestUrl = manifestUrl;
        }

        /** {@code http://host:port} (no trailing slash): the receiver appends the share paths. */
        public String baseUrl() {
            return ShareTransport.SCHEME + "://" + host + ":" + port;
        }
    }

    /**
     * Build the QR payload for a validated invite, or {@code null} when the inputs do not validate
     * (so a malformed address or token is never rendered into a scannable code). {@code host} is the
     * sender's hotspot IPv4; {@code port} is {@link ShareTransport#SHARE_PORT}.
     */
    public static String encode(String host, int port, String token, String manifestUrl) {
        SyncCredentialValidator.Result check =
                ShareInviteValidator.validate(host, port, token, manifestUrl);
        if (!check.valid) {
            return null;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("app", ShareTransport.QR_APP_TAG);
            json.put("addr", host + ":" + port);
            json.put("token", token);
            json.put("manifestUrl", manifestUrl);
            return json.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a scanned payload into a validated invite, or {@code null} when it is not a Share QR or
     * fails validation. The receiver fork calls this at the scan boundary; the scanned text is
     * untrusted input, so every field passes {@link ShareInviteValidator} before it is returned.
     */
    public static ShareInvite decode(String scannedJson) {
        try {
            JSONObject json = new JSONObject(scannedJson);
            if (!ShareTransport.QR_APP_TAG.equals(json.optString("app"))) {
                return null;   // not a Share invite
            }
            String addr = json.optString("addr", "");
            String token = json.optString("token", "");
            String manifestUrl = json.optString("manifestUrl", "");
            int colon = addr.lastIndexOf(':');   // IPv4 has no colons, so the last ':' splits host:port
            if (colon <= 0 || colon == addr.length() - 1) {
                return null;
            }
            String host = addr.substring(0, colon);
            int port = parsePort(addr.substring(colon + 1));
            SyncCredentialValidator.Result check =
                    ShareInviteValidator.validate(host, port, token, manifestUrl);
            if (!check.valid) {
                return null;
            }
            return new ShareInvite(host, port, token, manifestUrl);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse the port digits, returning -1 (an invalid port) on any non-numeric or out-of-range text. */
    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
