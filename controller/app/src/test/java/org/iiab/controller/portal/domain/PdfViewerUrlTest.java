package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pure-JVM tests for the pdf.js viewer URL builder. */
public class PdfViewerUrlTest {

    @Test public void buildsViewerUrlWithEncodedFile() {
        assertEquals(
                "http://localhost:8085/pdfjs/web/viewer.html?file=http%3A%2F%2Flocalhost%3A8085%2Fd%2Fa.pdf",
                PdfViewerUrl.forPdf("http://localhost:8085/d/a.pdf"));
    }

    @Test public void trimsInput() {
        assertEquals(
                "http://localhost:8085/pdfjs/web/viewer.html?file=http%3A%2F%2Flocalhost%3A8085%2Fa.pdf",
                PdfViewerUrl.forPdf("  http://localhost:8085/a.pdf  "));
    }

    @Test public void nullOrBlankReturnsNull() {
        assertNull(PdfViewerUrl.forPdf(null));
        assertNull(PdfViewerUrl.forPdf("   "));
    }
}
