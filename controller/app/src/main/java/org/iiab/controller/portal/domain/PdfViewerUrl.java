/*
 * ============================================================================
 * Name        : PdfViewerUrl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Builds the pdf.js viewer URL for a given viewer base (ADFA-4708).
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Builds the URL that opens a PDF inside a locally-served pdf.js viewer.
 * The viewer base comes from the served manifest (see {@link PdfViewerBuild}) — this class
 * only appends the {@code ?file=} parameter, so there is no hardcoded viewer version here.
 * pdf.js is served by the on-device nginx at the same origin as the PDF, so it can fetch the
 * file offline without CORS / mixed-content.
 */
public final class PdfViewerUrl {

    private PdfViewerUrl() {}

    /**
     * Viewer URL for {@code pdfUrl} using {@code viewerBase}
     * (e.g. {@code http://localhost:8085/pdfjs/6/web/viewer.html}), or {@code null}
     * when either input is blank.
     */
    public static String forPdf(String viewerBase, String pdfUrl) {
        if (viewerBase == null || pdfUrl == null) {
            return null;
        }
        String base = viewerBase.trim();
        String target = pdfUrl.trim();
        if (base.isEmpty() || target.isEmpty()) {
            return null;
        }
        return base + "?file=" + encode(target);
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
