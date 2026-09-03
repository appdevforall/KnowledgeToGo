package org.appdevforall.k2go.portal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-JVM tests for the portal auto-login policy (ADFA-5361). */
public class AutoLoginPolicyTest {

    @Test public void booksPagesOpenAsCalibreAdmin() {
        assertEquals("calibre", AutoLoginPolicy.serviceFor("http://localhost:8085/books/"));
        assertEquals("calibre", AutoLoginPolicy.serviceFor("http://localhost:8085/books"));
        // The entry point ADFA-5043 missed: a local book opened from "your books".
        assertEquals("calibre", AutoLoginPolicy.serviceFor("http://localhost:8085/books/book/12"));
        assertEquals("calibre", AutoLoginPolicy.serviceFor("http://box:8085/books/?q=verne"));
    }

    @Test public void coursePagesOpenAsKolibriAdmin() {
        assertEquals("kolibri", AutoLoginPolicy.serviceFor("http://localhost:8085/kolibri/"));
        assertEquals("kolibri", AutoLoginPolicy.serviceFor("http://127.0.0.1:8085/kolibri/learn#/home"));
    }

    @Test public void otherBoxPagesNeedNoSession() {
        assertNull(AutoLoginPolicy.serviceFor("http://localhost:8085/home"));
        assertNull(AutoLoginPolicy.serviceFor("http://localhost:8085/kiwix/"));
        assertNull(AutoLoginPolicy.serviceFor("http://localhost:8085/"));
        assertNull(AutoLoginPolicy.serviceFor("http://localhost:8085"));
    }

    /** An admin cookie must never be minted for, or injected against, a host that is not the box. */
    @Test public void externalHostsNeverAutoLogin() {
        assertNull(AutoLoginPolicy.serviceFor("http://example.org/books/"));
        assertNull(AutoLoginPolicy.serviceFor("https://gutenberg.org/books/book/12"));
        assertNull(AutoLoginPolicy.prefixFor("http://example.org/books/"));
    }

    @Test public void malformedUrlsNeedNoSession() {
        assertNull(AutoLoginPolicy.serviceFor(null));
        assertNull(AutoLoginPolicy.serviceFor(""));
        assertNull(AutoLoginPolicy.serviceFor("   "));
        assertNull(AutoLoginPolicy.serviceFor("/books/"));           // relative: no scheme
        assertNull(AutoLoginPolicy.serviceFor("localhost:8085/books"));
    }

    @Test public void prefixMatchesTheServedPath() {
        assertEquals("/books", AutoLoginPolicy.prefixFor("http://localhost:8085/books/book/12"));
        assertEquals("/kolibri", AutoLoginPolicy.prefixFor("http://localhost:8085/kolibri/"));
        assertNull(AutoLoginPolicy.prefixFor("http://localhost:8085/home"));
    }

    /** Service name and prefix come from one segment, so they cannot disagree. */
    @Test public void serviceAndPrefixStayInStep() {
        String url = "http://localhost:8085/BOOKS/book/12";
        assertEquals("calibre", AutoLoginPolicy.serviceFor(url));
        assertEquals("/books", AutoLoginPolicy.prefixFor(url));
    }
}
