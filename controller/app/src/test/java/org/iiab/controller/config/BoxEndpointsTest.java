package org.iiab.controller.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.iiab.controller.help.domain.Tier3DocsUrl;
import org.iiab.controller.portal.domain.PortalUrlResolver;
import org.junit.Test;

/** Regression guard for the D3 endpoint centralization: composed URLs must not change. */
public class BoxEndpointsTest {

    @Test public void baseHasNoTrailingSlash() {
        assertEquals("http://localhost:8085", BoxEndpoints.BASE);
        assertFalse(BoxEndpoints.BASE.endsWith("/"));
    }

    @Test public void composedPublicUrlsUnchanged() {
        assertEquals("http://localhost:8085/k2go-docs/", Tier3DocsUrl.BASE);
        assertEquals("http://localhost:8085/home", PortalUrlResolver.DEFAULT_URL);
    }
}
