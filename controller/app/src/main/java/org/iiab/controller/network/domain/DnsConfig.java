/*
 * ============================================================================
 * Name        : DnsConfig.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : DNS configuration: two mixed slots (primary/secondary), each one IPv4 or IPv6.
 * ============================================================================
 */
package org.iiab.controller.network.domain;

/**
 * Immutable DNS configuration: a {@code primary} and an optional {@code secondary}
 * nameserver. Each slot holds a SINGLE address that may be IPv4 <em>or</em> IPv6
 * (mixed allowed in any order); no comma-separated lists. This keeps the model and
 * validation simple while covering every combination the user might want.
 *
 * <p>Pure domain value object (no Android, no I/O). {@link #defaults()} is the
 * preconfigured set the app ships with, so the user never has to configure anything.
 */
public final class DnsConfig {

    private final String primary;
    private final String secondary;

    public DnsConfig(String primary, String secondary) {
        this.primary = norm(primary);
        this.secondary = norm(secondary);
    }

    /** Preconfigured defaults: Cloudflare (primary) + Google (secondary), both IPv4. */
    public static DnsConfig defaults() {
        return new DnsConfig("1.1.1.1", "8.8.8.8");
    }

    /** The primary nameserver (IPv4 or IPv6); "" if unset. */
    public String primary() { return primary; }

    /** The secondary nameserver (IPv4 or IPv6); "" if unset. */
    public String secondary() { return secondary; }

    public boolean hasSecondary() { return !secondary.isEmpty(); }

    public boolean isEmpty() { return primary.isEmpty() && secondary.isEmpty(); }

    /** Body for {@code /etc/resolv.conf}: one {@code nameserver X} line per non-empty slot. */
    public String toResolvConf() {
        StringBuilder sb = new StringBuilder();
        if (!primary.isEmpty()) sb.append("nameserver ").append(primary).append("\n");
        if (!secondary.isEmpty()) sb.append("nameserver ").append(secondary).append("\n");
        return sb.toString();
    }

    private static String norm(String s) { return s == null ? "" : s.trim(); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DnsConfig)) return false;
        DnsConfig d = (DnsConfig) o;
        return primary.equals(d.primary) && secondary.equals(d.secondary);
    }

    @Override public int hashCode() { return 31 * primary.hashCode() + secondary.hashCode(); }

    @Override public String toString() { return "DnsConfig{primary=" + primary + ", secondary=" + secondary + "}"; }
}
