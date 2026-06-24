package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure-JVM tests for portal URL resolution. */
public class PortalUrlResolverTest {

    @Test public void blankFallsBackToDefault() {
        assertEquals(PortalUrlResolver.DEFAULT_URL, PortalUrlResolver.resolve(null));
        assertEquals(PortalUrlResolver.DEFAULT_URL, PortalUrlResolver.resolve(""));
        assertEquals(PortalUrlResolver.DEFAULT_URL, PortalUrlResolver.resolve("   "));
    }

    @Test public void validUrlIsKept() {
        assertEquals("http://box/maps/", PortalUrlResolver.resolve("http://box/maps/"));
    }
}
