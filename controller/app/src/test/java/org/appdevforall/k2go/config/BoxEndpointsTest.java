package org.appdevforall.k2go.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.appdevforall.k2go.help.domain.Tier3DocsUrl;
import org.appdevforall.k2go.portal.domain.PortalUrlResolver;
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
