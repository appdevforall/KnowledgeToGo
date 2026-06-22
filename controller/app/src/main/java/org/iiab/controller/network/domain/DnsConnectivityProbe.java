/*
 * ============================================================================
 * Name        : DnsConnectivityProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain port: can a DNS server actually answer (netplan-try style).
 * ============================================================================
 */
package org.iiab.controller.network.domain;

/**
 * Domain port that probes whether a DNS server is a working resolver reachable
 * from the device. The Data layer implements it (a small UDP DNS query). Used for
 * the netplan-try style verification before committing a user-chosen DNS.
 */
public interface DnsConnectivityProbe {

    /**
     * @param dnsServer IPv4/IPv6 literal of the server to test
     * @param timeoutMs how long to wait for a reply
     * @return {@code true} if the server answered a DNS query within the timeout
     */
    boolean isReachable(String dnsServer, long timeoutMs);
}
