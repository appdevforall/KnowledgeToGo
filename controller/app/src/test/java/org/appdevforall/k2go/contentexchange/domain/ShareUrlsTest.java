package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Pure Share URL composition (K2GO-376): the fetch and complete endpoints are siblings of the one
 * manifest path, the token never appears, and a non-numeric itemRef or a non-origin base is rejected.
 */
public class ShareUrlsTest {

    private static final String BASE = "http://192.168.43.1:8095";
    private static final String MANIFEST = "/v1/share/abc123/manifest";

    @Test
    public void composesManifestFetchAndComplete() {
        assertEquals(BASE + "/v1/share/abc123/manifest", ShareUrls.manifest(BASE, MANIFEST));
        assertEquals(BASE + "/v1/share/abc123/fetch/0", ShareUrls.fetch(BASE, MANIFEST, "0"));
        assertEquals(BASE + "/v1/share/abc123/fetch/12", ShareUrls.fetch(BASE, MANIFEST, "12"));
        assertEquals(BASE + "/v1/share/abc123/complete", ShareUrls.complete(BASE, MANIFEST));
    }

    @Test
    public void sessionPrefixStripsManifestSuffix() {
        assertEquals("/v1/share/abc123", ShareUrls.sessionPrefix(MANIFEST));
    }

    @Test
    public void itemRefMustBeDecimalDigits() {
        assertTrue(ShareUrls.isValidItemRef("0"));
        assertTrue(ShareUrls.isValidItemRef("1234"));
        assertFalse(ShareUrls.isValidItemRef(""));
        assertFalse(ShareUrls.isValidItemRef(null));
        assertFalse(ShareUrls.isValidItemRef("1a"));
        assertFalse(ShareUrls.isValidItemRef("-1"));
        assertFalse(ShareUrls.isValidItemRef("0/complete"));   // no path smuggling
        assertFalse(ShareUrls.isValidItemRef("1234567890"));   // past the sane cap
    }

    @Test
    public void fetchRejectsNonNumericItemRef() {
        try {
            ShareUrls.fetch(BASE, MANIFEST, "../../v1/content");
            fail("expected rejection of a non-numeric itemRef");
        } catch (IllegalArgumentException expected) {
            // itemRef must be digits, so a path segment cannot be smuggled in
        }
    }

    @Test
    public void baseMustBeABareOrigin() {
        rejectBase("http://h:8095/");           // trailing slash
        rejectBase("http://h:8095/v1");         // carries a path
        rejectBase("ftp://h:8095");             // not http(s)
        rejectBase(null);
    }

    @Test
    public void manifestPathMustBeAManifestPath() {
        rejectManifest(BASE, "/v1/share/abc123/fetch/0");   // not a manifest
        rejectManifest(BASE, "/v1/share/../manifest");      // traversal
        rejectManifest(BASE, "/v1/share/x/manifest://evil");
        rejectManifest(BASE, "manifest");                   // not absolute
    }

    private static void rejectBase(String base) {
        try {
            ShareUrls.manifest(base, MANIFEST);
            fail("expected rejection of base: " + base);
        } catch (IllegalArgumentException expected) {
            // bare origin only
        }
    }

    private static void rejectManifest(String base, String path) {
        try {
            ShareUrls.fetch(base, path, "0");
            fail("expected rejection of manifest path: " + path);
        } catch (IllegalArgumentException expected) {
            // fail closed on a non-manifest path
        }
    }
}
