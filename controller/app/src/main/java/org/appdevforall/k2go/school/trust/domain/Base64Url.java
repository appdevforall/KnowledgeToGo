/*
 * ============================================================================
 * Name        : Base64Url.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: base64 for tokens and keys (pure JVM, API 24).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.domain;

import java.io.ByteArrayOutputStream;

/**
 * Base64 with no dependency: java.util.Base64 starts at API 26 and android.util.Base64 does not run
 * in a JVM test. Tokens use the URL alphabet with no padding; keys use the standard alphabet.
 */
public final class Base64Url {

    private static final char[] URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private Base64Url() {}

    /** URL alphabet, no padding. */
    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        for (; i + 2 < data.length; i += 3) {
            int n = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8 | (data[i + 2] & 0xff);
            out.append(URL[n >> 18 & 63]).append(URL[n >> 12 & 63]).append(URL[n >> 6 & 63]).append(URL[n & 63]);
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            out.append(URL[n >> 18 & 63]).append(URL[n >> 12 & 63]);
        } else if (rest == 2) {
            int n = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8;
            out.append(URL[n >> 18 & 63]).append(URL[n >> 12 & 63]).append(URL[n >> 6 & 63]);
        }
        return out.toString();
    }

    /** Standard alphabet with padding (keys in the JSON bodies, as Node writes them). */
    public static String encodeStandard(byte[] data) {
        StringBuilder s = new StringBuilder(encode(data).replace('-', '+').replace('_', '/'));
        while (s.length() % 4 != 0) s.append('=');
        return s.toString();
    }

    /** URL alphabet, no padding. null: not valid. */
    public static byte[] decode(String s) {
        return s == null ? null : decode(s, false);
    }

    /** Standard alphabet, with or without padding (keys in the JSON bodies). null: not valid. */
    public static byte[] decodeStandard(String s) {
        if (s == null) return null;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') end--;
        if (s.length() - end > 2) return null;
        return decode(s.substring(0, end), true);
    }

    private static byte[] decode(String s, boolean standard) {
        if (s.length() % 4 == 1) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4);
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = value(s.charAt(i), standard);
            if (v < 0) return null;
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write(acc >> bits & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static int value(char c, boolean standard) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == (standard ? '+' : '-')) return 62;
        if (c == (standard ? '/' : '_')) return 63;
        return -1;
    }
}
