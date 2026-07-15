/*
 * ============================================================================
 * Name        : WebViewVersion.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads the Chromium major from a WebView user-agent (ADFA-4708).
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the Chromium/WebView major version from a WebView user-agent string.
 * We never modify the ROM, so the System WebView version is device-dependent; the app uses
 * this to pick a pdf.js build the device can actually run (see {@link PdfViewerRouter}).
 * Pure logic — no android.* — so it is JVM unit-tested.
 */
public final class WebViewVersion {

    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");

    private WebViewVersion() {}

    /** Chromium major from {@code userAgent}, or {@code 0} when it cannot be determined. */
    public static int chromeMajor(String userAgent) {
        if (userAgent == null) {
            return 0;
        }
        Matcher m = CHROME.matcher(userAgent);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
