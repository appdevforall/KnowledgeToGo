package org.iiab.controller.network.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the DnsConfig value object. */
public class DnsConfigTest {

    @Test public void defaultsArePrimaryAndSecondary() {
        DnsConfig d = DnsConfig.defaults();
        assertEquals("1.1.1.1", d.primary());
        assertEquals("8.8.8.8", d.secondary());
        assertTrue(d.hasSecondary());
        assertFalse(d.isEmpty());
    }

    @Test public void resolvConfHasOneLinePerSlot() {
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", DnsConfig.defaults().toResolvConf());
    }

    @Test public void primaryOnlyOmitsSecondaryLine() {
        DnsConfig d = new DnsConfig("9.9.9.9", "");
        assertFalse(d.hasSecondary());
        assertEquals("nameserver 9.9.9.9\n", d.toResolvConf());
    }

    @Test public void trimsAndTreatsNullAsEmpty() {
        DnsConfig d = new DnsConfig("  1.1.1.1  ", null);
        assertEquals("1.1.1.1", d.primary());
        assertEquals("", d.secondary());
    }

    @Test public void equalsByValue() {
        assertEquals(DnsConfig.defaults(), new DnsConfig("1.1.1.1", "8.8.8.8"));
    }

    @Test public void emptyWhenBothBlank() {
        assertTrue(new DnsConfig("", "  ").isEmpty());
    }
}
