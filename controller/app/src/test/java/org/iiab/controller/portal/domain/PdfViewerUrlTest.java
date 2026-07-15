package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-JVM tests for the pdf.js viewer URL builder. */
public class PdfViewerUrlTest {

    private static final String BASE = "http://localhost:8085/pdfjs/6/web/viewer.html";

    @Test public void buildsViewerUrlWithEncodedFile() {
        assertEquals(
                BASE + "?file=http%3A%2F%2Flocalhost%3A8085%2Fd%2Fa.pdf",
                PdfViewerUrl.forPdf(BASE, "http://localhost:8085/d/a.pdf"));
    }

    @Test public void trimsInputs() {
        assertEquals(
                BASE + "?file=http%3A%2F%2Flocalhost%3A8085%2Fa.pdf",
                PdfViewerUrl.forPdf("  " + BASE + "  ", "  http://localhost:8085/a.pdf  "));
    }

    @Test public void nullOrBlankReturnsNull() {
        assertNull(PdfViewerUrl.forPdf(null, "http://x/a.pdf"));
        assertNull(PdfViewerUrl.forPdf(BASE, null));
        assertNull(PdfViewerUrl.forPdf("   ", "http://x/a.pdf"));
        assertNull(PdfViewerUrl.forPdf(BASE, "   "));
    }
}
