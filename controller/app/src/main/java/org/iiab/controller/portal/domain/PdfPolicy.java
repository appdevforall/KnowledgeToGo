/*
 * ============================================================================
 * Name        : PdfPolicy.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure test for whether a WebView resource is a PDF (ADFA-4708).
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

/**
 * Decides whether a resource the WebView is about to download is a PDF, so the
 * portal can open it in the local pdf.js viewer instead of ignoring it.
 * Pure (no android.*) so it is JVM-unit-testable. The internal-host check stays
 * with {@link NavigationPolicy}; this only classifies the content type.
 */
public final class PdfPolicy {

    private PdfPolicy() {}

    /** True when the URL / MIME type / content-disposition indicates a PDF. */
    public static boolean isPdf(String url, String mimeType, String contentDisposition) {
        if (mimeType != null && mimeType.trim().toLowerCase().startsWith("application/pdf")) {
            return true;
        }
        if (contentDisposition != null && contentDisposition.toLowerCase().contains(".pdf")) {
            return true;
        }
        if (url != null) {
            String path = url;
            int cut = path.indexOf('?');
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
            cut = path.indexOf('#');
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
            if (path.toLowerCase().endsWith(".pdf")) {
                return true;
            }
        }
        return false;
    }
}
