/*
 * ============================================================================
 * Name        : DnsValidator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Fail-closed validation: each DNS slot is a single valid IPv4 or IPv6 literal.
 * ============================================================================
 */
package org.iiab.controller.network.domain;

/**
 * Pure (framework-free) validation of {@link DnsConfig}. Each slot must be a single
 * valid IP literal, IPv4 <em>or</em> IPv6 (mixed allowed). Fail-closed: anything not
 * clearly a valid address is rejected. Mirrors {@code sync.domain.SyncCredentialValidator}.
 *
 * <p>IPv6 validation is pragmatic (hextet shape + at most one {@code ::}); it does not
 * accept embedded IPv4 or zone ids, which are not needed here.
 */
public final class DnsValidator {

    private DnsValidator() { }

    public static final class Result {
        public final boolean valid;
        public final String reason;
        private Result(boolean valid, String reason) { this.valid = valid; this.reason = reason; }
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String reason) { return new Result(false, reason); }
    }

    /**
     * Primary is required; secondary optional. Each must be a valid IP that is a USABLE
     * DNS server: loopback (127.0.0.0/8, ::1) and unspecified (0.0.0.0, ::) are rejected,
     * since a DNS server can't be the guest itself (and probing it would just fail).
     */
    public static Result validate(DnsConfig config) {
        if (config == null) return Result.fail("null config");
        if (config.primary().isEmpty()) return Result.fail("primary DNS is required");
        String p = checkServer(config.primary());
        if (p != null) return Result.fail("primary DNS " + p);
        if (config.hasSecondary()) {
            String sec = checkServer(config.secondary());
            if (sec != null) return Result.fail("secondary DNS " + sec);
        }
        return Result.ok();
    }

    /** @return null if {@code s} is a usable DNS server, otherwise a short reason. */
    private static String checkServer(String s) {
        if (!isValidIp(s)) return "is not a valid IP address";
        if (isLoopbackOrUnspecified(s)) return "can't be a loopback or unspecified address";
        return null;
    }

    /** A DNS server can't be loopback (the guest itself) or the unspecified address. */
    public static boolean isLoopbackOrUnspecified(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (isValidIpv4(v)) {
            return v.startsWith("127.") || v.equals("0.0.0.0");
        }
        String low = v.toLowerCase();
        return low.equals("::1") || low.equals("0:0:0:0:0:0:0:1")
            || low.equals("::") || low.equals("0:0:0:0:0:0:0:0");
    }

    /** A single address is valid if it parses as IPv4 OR IPv6. */
    public static boolean isValidIp(String s) {
        return isValidIpv4(s) || isValidIpv6(s);
    }

    public static boolean isValidIpv4(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    public static boolean isValidIpv6(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.contains(":::")) return false;
        int firstDouble = s.indexOf("::");
        boolean compressed = firstDouble >= 0;
        if (compressed && s.indexOf("::", firstDouble + 1) >= 0) return false;
        String[] groups = s.split(":", -1);
        int hextets = 0;
        for (String g : groups) {
            if (g.isEmpty()) continue;
            if (g.length() > 4) return false;
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) return false;
            }
            hextets++;
        }
        if (compressed) return hextets <= 7;
        return hextets == 8;
    }
}
