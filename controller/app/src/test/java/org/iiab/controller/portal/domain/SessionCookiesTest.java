package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Pure-JVM tests for the WebView cookie reconciliation (ADFA-5361). */
public class SessionCookiesTest {

    private static final String HEADER = "session=abc123; remember_token=7|deadbeef";

    @Test public void namesComeFromTheServerResponse() {
        assertEquals(Arrays.asList("session", "remember_token"), SessionCookies.names(HEADER));
    }

    @Test public void namesIgnoreJunkAndDuplicates() {
        assertEquals(Arrays.asList("a"), SessionCookies.names("a=1; ; noequals; =novalue; a=2"));
        assertTrue(SessionCookies.names(null).isEmpty());
        assertTrue(SessionCookies.names("").isEmpty());
    }

    /** The box-side login sets cookies at the root; the service, reached through its prefix, can
     *  set its own copy there — and "/books" and "/books/" are distinct cookie paths. */
    @Test public void everyPathTheCookieCanLiveAtIsCleared() {
        assertEquals(Arrays.asList("/", "/books", "/books/"), SessionCookies.clearPaths("/books"));
        assertEquals(Arrays.asList("/", "/books", "/books/"), SessionCookies.clearPaths("/books/"));
        assertEquals(Arrays.asList("/"), SessionCookies.clearPaths(null));
        assertEquals(Arrays.asList("/"), SessionCookies.clearPaths("/"));
        assertEquals(Arrays.asList("/"), SessionCookies.clearPaths("  "));
    }

    @Test public void clearDirectivesExpireEveryNameOnEveryPath() {
        List<String> out = SessionCookies.clearDirectives(HEADER, "/books");
        assertEquals(Arrays.asList(
                "session=; Path=/; Max-Age=0",
                "session=; Path=/books; Max-Age=0",
                "session=; Path=/books/; Max-Age=0",
                "remember_token=; Path=/; Max-Age=0",
                "remember_token=; Path=/books; Max-Age=0",
                "remember_token=; Path=/books/; Max-Age=0"), out);
    }

    @Test public void setDirectivesInstallTheFreshSessionHostWide() {
        assertEquals(Arrays.asList("session=abc123; path=/", "remember_token=7|deadbeef; path=/"),
                SessionCookies.setDirectives(HEADER));
    }

    @Test public void nothingToInstallMeansNothingToClear() {
        assertTrue(SessionCookies.clearDirectives(null, "/books").isEmpty());
        assertTrue(SessionCookies.clearDirectives("", "/books").isEmpty());
        assertTrue(SessionCookies.setDirectives(null).isEmpty());
    }

    /** A cookie value may contain '=' (base64 padding); only the first one splits name from value. */
    @Test public void valuesKeepTheirOwnEqualsSigns() {
        assertEquals(Arrays.asList("session"), SessionCookies.names("session=YWJjZA=="));
        assertEquals(Arrays.asList("session=YWJjZA==; path=/"),
                SessionCookies.setDirectives("session=YWJjZA=="));
    }
}
