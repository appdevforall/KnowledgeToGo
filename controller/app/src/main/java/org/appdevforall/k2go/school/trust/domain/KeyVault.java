/*
 * ============================================================================
 * Name        : KeyVault.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the school key, encrypted with the school password.
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a key from the password by PBKDF2-HMAC-SHA256. The PBKDF2 loop uses Mac
 * directly: the platform factory for PBKDF2WithHmacSHA256 starts at API 26, and minSdk is 24.
 * The associated data is the school id, so a sealed key of one school never opens as another.
 */
public final class KeyVault {

    /**
     * Measured 2026-11-26: about 6.5 s on the slowest lab phone (Nokia 6), 1.5 s on a Samsung A16.
     * Only create, restore and (later) the rings use it. Fewer rounds make a guess of the password of
     * a stolen backup faster. The file keeps the number, so it can change.
     */
    public static final int ITERATIONS = 120_000;
    private static final int MIN_ITERATIONS = 10_000;
    private static final int MAX_ITERATIONS = 5_000_000;

    private KeyVault() {}

    /** The sealed secret and what is necessary to open it with the password. */
    public static final class Sealed {
        public final int iterations;
        public final byte[] salt;
        public final byte[] iv;
        public final byte[] ciphertext;

        public Sealed(int iterations, byte[] salt, byte[] iv, byte[] ciphertext) {
            this.iterations = iterations;
            this.salt = salt;
            this.iv = iv;
            this.ciphertext = ciphertext;
        }

        /** null: a field that no seal() makes. */
        public static Sealed of(int iterations, byte[] salt, byte[] iv, byte[] ciphertext) {
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS || salt == null || salt.length != 16
                    || iv == null || iv.length != 12 || ciphertext == null || ciphertext.length < 17
                    || ciphertext.length > 4096) return null;
            return new Sealed(iterations, salt, iv, ciphertext);
        }
    }

    public static Sealed seal(byte[] secret, String password, String school) {
        return seal(secret, password, school, ITERATIONS);
    }

    static Sealed seal(byte[] secret, String password, String school, int iterations) {
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        r.nextBytes(salt);
        r.nextBytes(iv);
        byte[] key = pbkdf2(passwordBytes(password), salt, iterations);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(school.getBytes(StandardCharsets.UTF_8));
            return new Sealed(iterations, salt, iv, c.doFinal(secret));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The secret, or null when the password (or the school) is not the one of the seal. */
    public static byte[] open(Sealed s, String password, String school) {
        if (s == null || password == null || password.isEmpty()) return null;
        byte[] key = pbkdf2(passwordBytes(password), s.salt, s.iterations);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, s.iv));
            c.updateAAD(school.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(s.ciphertext);
        } catch (Exception e) {
            return null;   // AEADBadTagException: another password
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The same password typed on two keyboards can be two code point sequences: use NFC. */
    static byte[] passwordBytes(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018), one 32-byte block. */
    static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            mac.update(salt);
            byte[] u = mac.doFinal(new byte[] {0, 0, 0, 1});
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            return t;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }
}
