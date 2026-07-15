/*
 * ============================================================================
 * Name        : PdfViewerBuild.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One pdf.js build advertised by the served manifest (ADFA-4708).
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

/**
 * A pdf.js build available on the box, as declared in {@code /pdfjs/manifest.json}.
 * Immutable value object — no version-specific logic lives in the app; the set of builds is
 * whatever the manifest lists, so adding/removing a build is a server-side change only.
 */
public final class PdfViewerBuild {

    private final String id;
    private final String viewerPath;
    private final int minWebViewMajor;

    public PdfViewerBuild(String id, String viewerPath, int minWebViewMajor) {
        this.id = id;
        this.viewerPath = viewerPath;
        this.minWebViewMajor = minWebViewMajor;
    }

    public String getId() {
        return id;
    }

    /** Absolute path of the viewer, e.g. {@code /pdfjs/6/web/viewer.html}. */
    public String getViewerPath() {
        return viewerPath;
    }

    /** Minimum Chromium/WebView major this build supports. */
    public int getMinWebViewMajor() {
        return minWebViewMajor;
    }
}
