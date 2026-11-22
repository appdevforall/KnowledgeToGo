package org.appdevforall.k2go.networkpolicy.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostScopeTest {

    @Test
    public void localHostsStayLocal() {
        for (String h : new String[] {null, "", "localhost", "LOCALHOST", "box", "box.localhost", "k2go.local",
                "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.43.1", "169.254.1.1",
                "::1", "[::1]", "fe80::1", "fd12::3"}) {
            assertTrue(h, HostScope.isLocal(h));
        }
    }

    @Test
    public void internetHostsAreNotLocal() {
        for (String h : new String[] {"k2go-download.appdevforall.org", "studio.learningequality.org",
                "tiles.example.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "1.2.3", "300.1.1.1",
                "2001:4860::8888", "localhost.evil.com"}) {
            assertFalse(h, HostScope.isLocal(h));
        }
    }
}
