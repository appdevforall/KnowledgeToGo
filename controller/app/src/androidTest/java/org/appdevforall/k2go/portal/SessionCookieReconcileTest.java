package org.appdevforall.k2go.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.webkit.CookieManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.appdevforall.k2go.portal.domain.AutoLoginPolicy;
import org.appdevforall.k2go.portal.domain.SessionCookies;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * ADFA-5361: on-device proof that the reconcile directives do what the pure tests describe.
 * The JVM tests pin the STRINGS; only the real WebView cookie store can say whether
 * "Max-Age=0" at a given Path actually deletes, and whether a deeper-path copy outranks the
 * one we install — which is the mechanism that turned one guest page load into a permanent
 * guest session.
 *
 * <p>Uses a test-only origin so it never touches the box's real cookies.
 */
@RunWith(AndroidJUnit4.class)
public class SessionCookieReconcileTest {

    private static final String ORIGIN = "http://cookie-reconcile.test/";
    private static final String FRESH = "session=fresh-admin; remember_token=7|fresh";

    private CookieManager cm;

    private void wipe() {
        for (String name : new String[]{"session", "remember_token"}) {
            for (String path : new String[]{"/", "/books", "/books/"}) {
                cm.setCookie(ORIGIN, name + "=; Path=" + path + "; Max-Age=0");
            }
        }
        cm.flush();
    }

    @Before public void setUp() {
        cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        wipe();
    }

    @After public void tearDown() {
        wipe();
    }

    /** The stale copy the old code could never replace: same name, deeper path. */
    @Test public void deeperPathCookieOutranksTheRootOne() {
        cm.setCookie(ORIGIN, "session=stale-guest; path=/books");
        cm.setCookie(ORIGIN, "session=fresh-admin; path=/");
        cm.flush();

        String header = cm.getCookie(ORIGIN + "books/");
        assertTrue("both copies are sent: " + header, header.contains("stale-guest"));
        assertTrue("both copies are sent: " + header, header.contains("fresh-admin"));
        // The service reads the first "session" it is given, and that is the stale one.
        assertTrue("stale copy is served first: " + header,
                header.indexOf("stale-guest") < header.indexOf("fresh-admin"));
    }

    /** The fix: clearing every candidate path first leaves only what we just installed. */
    @Test public void clearThenSetLeavesOnlyTheFreshSession() {
        cm.setCookie(ORIGIN, "session=stale-guest; path=/books");
        cm.setCookie(ORIGIN, "session=stale-guest; path=/books/");
        cm.setCookie(ORIGIN, "remember_token=1|stale; path=/books");
        cm.flush();

        String prefix = AutoLoginPolicy.prefixFor("http://localhost:8085/books/book/12");
        assertEquals("/books", prefix);
        for (String directive : SessionCookies.clearDirectives(FRESH, prefix)) {
            cm.setCookie(ORIGIN, directive);
        }
        for (String directive : SessionCookies.setDirectives(FRESH)) {
            cm.setCookie(ORIGIN, directive);
        }
        cm.flush();

        String header = cm.getCookie(ORIGIN + "books/book/12");
        assertFalse("stale session survived: " + header, header.contains("stale-guest"));
        assertFalse("stale remember_token survived: " + header, header.contains("1|stale"));
        assertTrue("fresh session missing: " + header, header.contains("session=fresh-admin"));
        assertTrue("fresh remember_token missing: " + header, header.contains("remember_token=7|fresh"));
    }

    /** Max-Age=0 must delete at the exact path it names, not only at the root. */
    @Test public void clearDeletesAtEveryNamedPath() {
        cm.setCookie(ORIGIN, "session=at-root; path=/");
        cm.setCookie(ORIGIN, "session=at-prefix; path=/books");
        cm.setCookie(ORIGIN, "session=at-prefix-slash; path=/books/");
        cm.flush();

        for (String directive : SessionCookies.clearDirectives("session=x", "/books")) {
            cm.setCookie(ORIGIN, directive);
        }
        cm.flush();

        String header = cm.getCookie(ORIGIN + "books/");
        assertTrue("expected no session cookie left, got: " + header,
                header == null || !header.contains("session="));
    }
}
