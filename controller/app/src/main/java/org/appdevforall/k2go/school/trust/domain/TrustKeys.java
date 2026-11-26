/*
 * ============================================================================
 * Name        : TrustKeys.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: ECDSA P-256 keys, school id, sign and verify.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Platform crypto only (java.security): no new library (K2G-5). */
public final class TrustKeys {

    private static final String ALGORITHM = "SHA256withECDSA";
    // The order n of the curve P-256 (FIPS 186-4, D.1.2.3): a key on any other curve is refused.
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
    private static final String PROOF_TAG = "k2go-class-proof";

    private TrustKeys() {}

    /** The school id: the first 16 hex chars of SHA-256 of the root public key (SPKI DER). */
    public static String schoolId(byte[] rootKeySpki) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(rootKeySpki);
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A P-256 public key from SPKI DER; null when it is not one. */
    public static PublicKey publicKey(byte[] spki) {
        if (spki == null) return null;
        try {
            PublicKey k = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            if (!(k instanceof ECPublicKey)) return null;
            return P256_ORDER.equals(((ECPublicKey) k).getParams().getOrder()) ? k : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean verify(byte[] spki, byte[] data, byte[] sig) {
        PublicKey key = publicKey(spki);
        if (key == null || sig == null) return false;
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (Exception e) {
            return false;   // a malformed DER signature throws
        }
    }

    public static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The bytes that a teacher box signs to prove that it holds the key of its certificate. host: the
     * address of the box (IP:port) as the student phone used it, so a false box that relays the nonce
     * to a real box gets a proof for another address. null: a field is not 1 to 100 printable ASCII
     * chars with no space (no field can carry a newline and pass for two fields).
     */
    public static byte[] proofBytes(String nonce, String code, String host) {
        if (!proofField(nonce) || !proofField(code) || !proofField(host)) return null;
        return (PROOF_TAG + "\n" + nonce + "\n" + code + "\n" + host).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean proofField(String s) {
        if (s == null || s.isEmpty() || s.length() > 100) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7e) return false;
        }
        return true;
    }
}
