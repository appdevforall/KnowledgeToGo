/*
 * ============================================================================
 * Name        : WebViewProxyConfigurator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Wraps WebView SOCKS proxy override (VPN) behind a small adapter.
 * ============================================================================
 */
package org.iiab.controller.portal.data;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

/** Thin adapter over androidx ProxyController for the portal's SOCKS proxy. */
public final class WebViewProxyConfigurator {

    private WebViewProxyConfigurator() {}

    public static boolean isSupported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
    }

    /** Route WebView traffic through socks5://127.0.0.1:{port}; runs {@code onReady} when applied. */
    public static void applySocks(int port, Executor executor, Runnable onReady) {
        ProxyConfig config = new ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:" + port)
                .build();
        ProxyController.getInstance().setProxyOverride(config, executor, onReady);
    }

    /** Remove any proxy override (call on teardown). */
    public static void clear(Runnable onCleared) {
        ProxyController.getInstance().clearProxyOverride(Runnable::run, onCleared);
    }
}
