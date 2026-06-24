package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the portal navigation policy. */
public class NavigationPolicyTest {

    @Test public void localHostsAreInternal() {
        assertTrue(NavigationPolicy.isInternalHost("box"));
        assertTrue(NavigationPolicy.isInternalHost("localhost"));
        assertTrue(NavigationPolicy.isInternalHost("127.0.0.1"));
        assertTrue(NavigationPolicy.isInternalHost("LocalHost")); // case-insensitive
    }

    @Test public void remoteHostsAreExternal() {
        assertTrue(NavigationPolicy.isExternalHost("youtube.com"));
        assertTrue(NavigationPolicy.isExternalHost("example.org"));
        assertFalse(NavigationPolicy.isInternalHost(null));
    }
}
