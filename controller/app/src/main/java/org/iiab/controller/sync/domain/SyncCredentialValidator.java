/*
 * ============================================================================
 * Name        : SyncCredentialValidator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule: what counts as a *safe* peer-to-peer sync
 *               credential. Closes tech-debt item S1 (rsyncd.conf / rsync://
 *               URL injection from QR-scanned credentials).
 * ============================================================================
 */
package org.iiab.controller.sync.domain;

/**
 * Pure (framework-free) validation of peer-to-peer sync credentials.
 *
 * <p>The sync handshake transports a host IP, port, username and password
 * inside a QR code that the receiving device scans. Those values are
 * <strong>untrusted input</strong>: they are later interpolated into an
 * {@code rsyncd.conf} file (server side) and into a {@code rsync://user@host:port/...}
 * URL (client side). Without validation, a crafted QR code could inject extra
 * {@code rsyncd.conf} directives (e.g. a newline followed by a new module
 * section) or break out of the URL — see tech-debt item <b>S1</b>.
 *
 * <p>This class is the single source of truth for "what is a valid credential".
 * It lives in the domain layer: no {@code android.*}, no networking, no I/O, so
 * it is fully unit-testable on a plain JVM and reusable by any other injection
 * fix (e.g. S4/D2) that needs to validate or quote shell/config input.
 *
 * <p>The policy is intentionally strict and fail-closed. Legitimate credentials
 * are produced by {@code SyncHandshakeHelper.generateSecurePassword()}
 * (12 alphanumeric chars) and a fixed username, so a conservative character set
 * rejects attacks without rejecting any real value.
 */
public final class SyncCredentialValidator {

    /** Max username length accepted in {@code auth users} / URL userinfo. */
    private static final int MAX_USERNAME_LEN = 64;
    /** Max password length accepted in the rsync password file. */
    private static final int MAX_PASSWORD_LEN = 128;
    /** Max host/IP length (DNS label limit is 253). */
    private static final int MAX_HOST_LEN = 253;

    private SyncCredentialValidator() {
        // Static utility; not instantiable.
    }

    /** Outcome of a validation, with a machine-readable reason on failure. */
    public static final class Result {
        public final boolean valid;
        /** Null when {@link #valid}; otherwise a short, log-safe reason. */
        public final String reason;

        private Result(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static Result ok() {
            return new Result(true, null);
        }

        public static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * Validate a full set of scanned sync credentials. Call this at the parse
     * boundary (QR decode); reject the whole handshake if it returns invalid.
     */
    public static Result validateCredentials(String ip, int port, String user, String pass) {
        if (!isValidHost(ip)) {
            return Result.fail("invalid host/ip");
        }
        if (!isValidPort(port)) {
            return Result.fail("invalid port");
        }
        if (!isValidUsername(user)) {
            return Result.fail("invalid username");
        }
        if (!isValidPassword(pass)) {
            return Result.fail("invalid password");
        }
        return Result.ok();
    }

    /**
     * A username safe for both the {@code rsync://USER@host} URL userinfo and
     * the {@code auth users = USER} directive: a non-empty run of
     * {@code [A-Za-z0-9_-]} starting with a letter, digit or underscore.
     * Matches the fixed {@code iiab_peer} account.
     */
    public static boolean isValidUsername(String user) {
        if (user == null || user.isEmpty() || user.length() > MAX_USERNAME_LEN) {
            return false;
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || (c == '-' && i > 0); // '-' allowed, but not as the first char
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * A password safe to write as a single line in the rsync password / secrets
     * file: printable ASCII only, no whitespace and no control characters (so it
     * cannot add a second secrets line or a stray {@code rsyncd.conf} directive).
     * The app's generated alphanumeric passwords satisfy this.
     */
    public static boolean isValidPassword(String pass) {
        if (pass == null || pass.isEmpty() || pass.length() > MAX_PASSWORD_LEN) {
            return false;
        }
        for (int i = 0; i < pass.length(); i++) {
            char c = pass.charAt(i);
            // Printable ASCII excluding space (0x20) and DEL (0x7f).
            if (c <= 0x20 || c >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    /**
     * A host that is either a dotted IPv4 literal or a DNS hostname, using only
     * {@code [A-Za-z0-9.-]}. Rejects anything containing {@code @ : / \\},
     * whitespace or control characters that could break out of the URL.
     */
    public static boolean isValidHost(String host) {
        if (host == null || host.isEmpty() || host.length() > MAX_HOST_LEN) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '-';
            if (!ok) {
                return false;
            }
        }
        // Reject leading/trailing dot or dash and empty labels (".." / "a..b").
        if (host.charAt(0) == '.' || host.charAt(0) == '-'
                || host.charAt(host.length() - 1) == '.'
                || host.charAt(host.length() - 1) == '-'
                || host.contains("..")) {
            return false;
        }
        return true;
    }

    /** TCP port in the valid 1..65535 range. */
    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /**
     * True if {@code value} is safe to embed verbatim on a single line of an
     * {@code rsyncd.conf} file: no control characters (notably CR/LF, which
     * would let an attacker append new directives or module sections) and no
     * NUL. Use this for app-controlled values such as the shared directory path.
     */
    public static boolean isSafeConfigValue(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0' || c < 0x20 && c != '\t') {
                return false;
            }
        }
        return true;
    }
}
