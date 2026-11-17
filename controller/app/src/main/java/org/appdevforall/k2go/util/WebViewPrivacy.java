/*
 * ============================================================================
 * Name        : WebViewPrivacy.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-522. The system WebView asks Google's autofill server about every page with
 *               a form field (a search box), from inside the app process, where the page's own
 *               request hooks cannot see it. Every page here is the local box, so that one host is
 *               sent to a closed local port and the query never leaves the phone.
 *               https://developer.android.com/reference/androidx/webkit/ProxyConfig.Builder#setReverseBypassEnabled(boolean)
 * ============================================================================
 */
package org.appdevforall.k2go.util;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

public final class WebViewPrivacy {

    private WebViewPrivacy() {}

    /** Hosts that the WebView engine contacts on its own and that must not leave the phone. */
    private static final String[] BLOCKED_HOSTS = {"content-autofill.googleapis.com"};

    private static boolean applied;

    /** Once per process, before the first page loads. Call on the main thread. */
    public static void apply() {
        if (applied) return;
        applied = true;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
            return;   // old WebView: nothing to do
        }
        // Reverse bypass: only the listed hosts use the "proxy"; port 1 refuses at once.
        ProxyConfig.Builder b = new ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:1")
                .setReverseBypassEnabled(true);
        for (String h : BLOCKED_HOSTS) b.addBypassRule(h);
        ProxyController.getInstance().setProxyOverride(b.build(), Runnable::run, () -> { });
    }
}
