package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure-JVM tests for extracting the Chromium major from a WebView user-agent. */
public class WebViewVersionTest {

    @Test public void readsChromeMajor() {
        String ua = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Version/4.0 Chrome/118.0.5993.88 Mobile Safari/537.36";
        assertEquals(118, WebViewVersion.chromeMajor(ua));
    }

    @Test public void modernWebView() {
        assertEquals(140, WebViewVersion.chromeMajor("... Chrome/140.0.7339.207 ..."));
    }

    @Test public void unknownReturnsZero() {
        assertEquals(0, WebViewVersion.chromeMajor(null));
        assertEquals(0, WebViewVersion.chromeMajor("Mozilla/5.0 no chrome token"));
    }
}
