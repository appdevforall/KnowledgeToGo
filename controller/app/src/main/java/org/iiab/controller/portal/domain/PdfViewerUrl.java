/*
 * ============================================================================
 * Name        : PdfViewerUrl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Single source of truth for the local pdf.js viewer route (ADFA-4708).
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Builds the URL that opens a PDF inside the locally-served pdf.js viewer.
 * pdf.js is served by the on-device nginx at the same origin as the PDF, so the
 * viewer can fetch the file offline without CORS / mixed-content. Change the route here only.
 */
public final class PdfViewerUrl {

    /** pdf.js viewer served by the local IIAB box. */
    public static final String VIEWER = "http://localhost:8085/pdfjs/web/viewer.html";

    private PdfViewerUrl() {}

    /** Viewer URL for {@code pdfUrl}, or {@code null} when the input is blank. */
    public static String forPdf(String pdfUrl) {
        if (pdfUrl == null) {
            return null;
        }
        String target = pdfUrl.trim();
        if (target.isEmpty()) {
            return null;
        }
        return VIEWER + "?file=" + encode(target);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always available; unreachable.
            return value;
        }
    }
}
