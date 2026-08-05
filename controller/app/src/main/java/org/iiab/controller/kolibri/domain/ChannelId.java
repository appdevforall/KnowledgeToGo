/*
 * ============================================================================
 * Name        : ChannelId.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule: what counts as a usable Kolibri channel or node
 *               identifier, and how to normalise one. Pure JVM (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

/**
 * Guard for Kolibri channel and content-node identifiers.
 *
 * <p>Kolibri identifies channels and nodes by a UUID rendered as <b>32 lowercase
 * hex characters without dashes</b>. Two different things get confused with it and
 * both fail in ways that are hard to diagnose:
 *
 * <ul>
 *   <li>A <b>dashed UUID</b> copied from a URL. Kolibri's own serializer
 *       normalises it, but our requests and any local bookkeeping should not
 *       depend on that, so {@link #normalise(String)} strips the dashes.</li>
 *   <li>A <b>channel token</b> — the ten-character pronounceable proquint shown
 *       in Studio as {@code xxxxx-xxxxx}. It is <em>not</em> an id. Passing one
 *       where an id is expected ends in a 404 from the downloader with no useful
 *       message, so {@link #isValid(String)} rejects it and the caller is
 *       expected to resolve it first via {@code /k2go-api/kolibri/resolve/:id}.</li>
 * </ul>
 *
 * <p>Beyond correctness this is a safety boundary, the same one
 * {@link org.iiab.controller.deploy.domain.ModuleName} draws for module names:
 * an identifier reaching this class ends up in a request the box acts on, so
 * anything not explicitly allowed is rejected. Fail-closed.
 *
 * <p>No {@code android.*} and no HTTP, so it is unit-testable on a plain JVM.
 */
public final class ChannelId {

    /** A Kolibri UUID is exactly 32 hex characters once the dashes are gone. */
    private static final int LENGTH = 32;

    private ChannelId() {
        // Static utility; not instantiable.
    }

    /**
     * Canonical form of {@code raw}: trimmed, dashes removed, lowercased — or
     * {@code null} if the result is not a valid identifier.
     *
     * <p>Returning {@code null} rather than throwing keeps the common
     * "filter out what is not usable" path free of exception handling; callers
     * that need to fail loudly should check and raise their own error.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-') {
                continue;
            }
            // Uppercase hex is a legitimate way to write a UUID; fold it.
            if (c >= 'A' && c <= 'F') {
                c = (char) (c - 'A' + 'a');
            }
            sb.append(c);
        }

        String candidate = sb.toString();
        return isCanonical(candidate) ? candidate : null;
    }

    /**
     * True if {@code raw} can be normalised into a usable identifier. Accepts the
     * dashed and uppercase spellings; rejects tokens, empty input and anything
     * that is not hex.
     */
    public static boolean isValid(String raw) {
        return normalise(raw) != null;
    }

    /**
     * True if {@code value} is <em>already</em> in canonical form: exactly 32
     * lowercase hex characters. Used to assert on values that should have been
     * normalised earlier rather than normalising them again silently.
     */
    public static boolean isCanonical(String value) {
        if (value == null || value.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if {@code raw} looks like a Studio channel <em>token</em> rather than
     * an id: ten characters of {@code [a-z0-9]}, optionally split by a dash into
     * two groups of five.
     *
     * <p>Only used to tell the user <em>why</em> their input was rejected — "that
     * is a token, resolve it first" beats "invalid identifier". It deliberately
     * does not accept tokens as ids.
     */
    public static boolean looksLikeToken(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim().toLowerCase();
        if (s.length() == 11 && s.charAt(5) == '-') {
            s = s.substring(0, 5) + s.substring(6);
        }
        if (s.length() != 10) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        // A 10-char all-hex string is ambiguous in principle, but it can never be
        // a valid id (those are 32), so treating it as a token is the useful read.
        return true;
    }
}
