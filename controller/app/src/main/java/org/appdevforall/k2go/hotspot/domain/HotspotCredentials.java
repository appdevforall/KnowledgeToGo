/*
 * ============================================================================
 * Name        : HotspotCredentials.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-127. Pure rules for the local hotspot's network name and password. No
 *               Android, no IO: unit-testable on a plain JVM. Android forces a Wi-Fi Direct
 *               group name to start with "DIRECT-"; "K2GO-" marks it as ours. The factory
 *               defaults are shared (not per-device random): an unedited hotspot uses a known
 *               password so a nearby device can join without being told it, while the owner can
 *               change the name and password (and revert to the defaults). The name keeps a
 *               stable per-device 3-digit suffix so two nearby K2Go hotspots do not collide.
 *               Validation mirrors Android's constraints (name <= 32 UTF-8 bytes; WPA2
 *               passphrase 8..63 characters; no control characters).
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Pure hotspot name/password rules and factory defaults (K2GO-127). See the file header. */
public final class HotspotCredentials {

    private HotspotCredentials() {}

    /** Android requires the "DIRECT-" prefix on a Wi-Fi Direct group name; "K2GO-" marks it ours. */
    public static final String PREFIX = "DIRECT-K2GO-";
    /** Android's limit for the whole group name, in UTF-8 bytes. */
    public static final int MAX_NAME_BYTES = 32;
    /** WPA2 passphrase length bounds. */
    public static final int MIN_PASSWORD = 8;
    public static final int MAX_PASSWORD = 63;
    /** Shared factory-default password (K2GO-127, option 6-B): known, so an unedited hotspot just works. */
    public static final String DEFAULT_PASSWORD = "KnowledgeToGo";
    /** How many digits the stable per-device name suffix has. */
    public static final int SUFFIX_DIGITS = 3;

    /** Why a value cannot be used, or {@link #NONE} when it is valid. The UI maps this to a localized string. */
    public enum Problem { NONE, EMPTY, TOO_SHORT, TOO_LONG, CONTROL_CHAR }

    /** The full group name from the editable [suffix] (the part after {@link #PREFIX}). */
    public static String name(String suffix) {
        return PREFIX + (suffix == null ? "" : suffix.trim());
    }

    /** A stable per-device default suffix: {@value #SUFFIX_DIGITS} digits. Pass a seeded Random in tests. */
    public static String defaultSuffix(Random random) {
        int bound = (int) Math.pow(10, SUFFIX_DIGITS);          // 1000 for 3 digits
        return String.format("%0" + SUFFIX_DIGITS + "d", random.nextInt(bound));
    }

    /** Validates the editable name suffix against Android's rules. */
    public static Problem nameProblem(String suffix) {
        String s = suffix == null ? "" : suffix.trim();
        if (s.isEmpty()) return Problem.EMPTY;
        if (hasControlChar(s)) return Problem.CONTROL_CHAR;
        if (name(s).getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) return Problem.TOO_LONG;
        return Problem.NONE;
    }

    /** Validates a WPA2 passphrase. */
    public static Problem passwordProblem(String password) {
        if (password == null || password.isEmpty()) return Problem.EMPTY;
        if (hasControlChar(password)) return Problem.CONTROL_CHAR;
        if (password.length() < MIN_PASSWORD) return Problem.TOO_SHORT;
        if (password.length() > MAX_PASSWORD) return Problem.TOO_LONG;
        return Problem.NONE;
    }

    public static boolean isValidNameSuffix(String suffix) { return nameProblem(suffix) == Problem.NONE; }
    public static boolean isValidPassword(String password) { return passwordProblem(password) == Problem.NONE; }

    private static boolean hasControlChar(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }
}
