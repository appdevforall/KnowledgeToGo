/*
 * ============================================================================
 * Name        : PdfViewerRouter.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Picks the best pdf.js build for a device's WebView (ADFA-4708).
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

import java.util.List;

/**
 * Chooses the most modern pdf.js build the device's WebView can run: among the builds whose
 * {@code minWebViewMajor} the WebView satisfies, the one with the highest requirement.
 * Returns {@code null} when none qualify (very old WebView, or no builds served) — the caller
 * then falls back to downloading the PDF. Pure logic, JVM unit-tested. Data-driven: no build
 * ids are hardcoded, so removing a build from the manifest needs no app change.
 */
public final class PdfViewerRouter {

    private PdfViewerRouter() {}

    public static PdfViewerBuild pick(int chromeMajor, List<PdfViewerBuild> builds) {
        if (builds == null || chromeMajor <= 0) {
            return null;
        }
        PdfViewerBuild best = null;
        for (PdfViewerBuild b : builds) {
            if (b == null || b.getViewerPath() == null) {
                continue;
            }
            if (b.getMinWebViewMajor() <= chromeMajor
                    && (best == null || b.getMinWebViewMajor() > best.getMinWebViewMajor())) {
                best = b;
            }
        }
        return best;
    }
}
