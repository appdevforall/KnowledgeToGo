package org.iiab.controller.network.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for DNS address validation (IPv4 or IPv6 per slot). */
public class DnsValidatorTest {

    @Test public void acceptsIpv4() {
        assertTrue(DnsValidator.isValidIpv4("8.8.8.8"));
        assertTrue(DnsValidator.isValidIpv4("1.1.1.1"));
    }

    @Test public void rejectsBadIpv4() {
        assertFalse(DnsValidator.isValidIpv4("aaa.bbb.ccc"));
        assertFalse(DnsValidator.isValidIpv4("256.0.0.1"));
        assertFalse(DnsValidator.isValidIpv4("1.1.1"));
        assertFalse(DnsValidator.isValidIpv4("01.1.1.1"));
        assertFalse(DnsValidator.isValidIpv4("1.1.1.1.1"));
    }

    @Test public void acceptsIpv6() {
        assertTrue(DnsValidator.isValidIpv6("2001:4860:4860::8888"));
        assertTrue(DnsValidator.isValidIpv6("::1"));
        assertTrue(DnsValidator.isValidIpv6("fe80::1"));
    }

    @Test public void rejectsBadIpv6() {
        assertFalse(DnsValidator.isValidIpv6("xxxx::yyyy"));
        assertFalse(DnsValidator.isValidIpv6("2001:::1"));
        assertFalse(DnsValidator.isValidIpv6("12345::"));
        assertFalse(DnsValidator.isValidIpv6("gggg::"));
    }

    @Test public void mixedSlotsAllowedEitherOrder() {
        assertTrue(DnsValidator.validate(new DnsConfig("1.1.1.1", "2001:4860:4860::8888")).valid);
        assertTrue(DnsValidator.validate(new DnsConfig("2001:4860:4860::8888", "8.8.8.8")).valid);
    }

    @Test public void primaryRequired() {
        assertFalse(DnsValidator.validate(new DnsConfig("", "8.8.8.8")).valid);
    }

    @Test public void secondaryOptional() {
        assertTrue(DnsValidator.validate(new DnsConfig("1.1.1.1", "")).valid);
    }

    @Test public void garbageRejected() {
        DnsValidator.Result r = DnsValidator.validate(new DnsConfig("aaa.bbb.ccc", "xxx.yyy.zzz"));
        assertFalse(r.valid);
        assertNotNull(r.reason);
    }
}
