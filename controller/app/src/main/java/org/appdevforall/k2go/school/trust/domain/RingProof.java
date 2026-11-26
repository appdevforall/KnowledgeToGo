/*
 * ============================================================================
 * Name        : RingProof.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the PIN and the HMAC values of the setup ring.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 with the ring PIN as the key, hex; the box makes the same values
 * (static/dashboard/school/ring.ts). The phone proves that it knows the PIN when it joins, and the
 * ring proves it when it answers, so a false ring with no PIN cannot give a phone its root.
 * The limits of a 4-digit PIN: school-trust-design.md, section 8.
 */
public final class RingProof {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RingProof() {}

    /** A new ring PIN: 4 digits. */
    public static String newPin() {
        return String.format(java.util.Locale.ROOT, "%04d", RANDOM.nextInt(10000));
    }

    /** A new nonce for one join: 24 base64url chars. */
    public static String newNonce() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64Url.encode(b);
    }

    /** A new certificate serial: 16 hex chars. */
    public static String newSerial() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder s = new StringBuilder(16);
        for (byte x : b) s.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return s.toString();
    }

    /** The proof of a student join; with the teacher public key, of a teacher join. */
    public static String join(String pin, String nonce, String teacherKey) {
        return teacherKey == null ? mac(pin, "k2go-ring-join", nonce) : mac(pin, "k2go-ring-join", nonce, teacherKey);
    }

    /** The answer of a student ring is the root of this ring. */
    public static boolean rootAnswer(String pin, String nonce, String root, String mac) {
        return same(mac, mac(pin, "k2go-ring-root", nonce, root));
    }

    /** The answer of a teacher ring is the root and the certificate of this ring. */
    public static boolean certAnswer(String pin, String nonce, String root, String cert, String mac) {
        return same(mac, mac(pin, "k2go-ring-cert", nonce, root, cert));
    }

    static String mac(String pin, String... fields) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(pin.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = m.doFinal(String.join("\n", fields).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte x : d) hex.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
