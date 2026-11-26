/*
 * ============================================================================
 * Name        : SignedToken.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: "<base64url body>.<base64url signature>".
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

/**
 * A signed token. The signature covers the body bytes as they are, so no JSON canonical form is
 * necessary on either side (the box: static/dashboard/school/trust.ts).
 */
public final class SignedToken {

    public static final int MAX_LENGTH = 4096;

    public final byte[] body;
    public final byte[] sig;

    private SignedToken(byte[] body, byte[] sig) {
        this.body = body;
        this.sig = sig;
    }

    public static SignedToken of(byte[] body, byte[] sig) {
        return new SignedToken(body.clone(), sig.clone());
    }

    /** null: not a token (too long, not two parts, not base64url). */
    public static SignedToken parse(String token) {
        if (token == null || token.length() > MAX_LENGTH) return null;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) return null;
        byte[] body = Base64Url.decode(token.substring(0, dot));
        byte[] sig = Base64Url.decode(token.substring(dot + 1));
        return body == null || sig == null ? null : new SignedToken(body, sig);
    }

    public String compact() {
        return Base64Url.encode(body) + "." + Base64Url.encode(sig);
    }
}
