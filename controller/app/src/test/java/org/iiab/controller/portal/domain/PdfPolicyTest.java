package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for PDF classification. */
public class PdfPolicyTest {

    @Test public void detectsByMimeType() {
        assertTrue(PdfPolicy.isPdf("http://localhost:8085/x", "application/pdf", null));
        assertTrue(PdfPolicy.isPdf("http://localhost:8085/x", "application/pdf; charset=binary", null));
    }

    @Test public void detectsByExtension() {
        assertTrue(PdfPolicy.isPdf("http://localhost:8085/docs/manual.pdf", null, null));
        assertTrue(PdfPolicy.isPdf("http://localhost:8085/docs/manual.PDF?v=2", "application/octet-stream", null));
    }

    @Test public void detectsByContentDisposition() {
        assertTrue(PdfPolicy.isPdf("http://localhost:8085/dl", "application/octet-stream",
                "attachment; filename=\"guide.pdf\""));
    }

    @Test public void rejectsNonPdf() {
        assertFalse(PdfPolicy.isPdf("http://localhost:8085/home", "text/html", null));
        assertFalse(PdfPolicy.isPdf("http://localhost:8085/app.apk",
                "application/vnd.android.package-archive", null));
        assertFalse(PdfPolicy.isPdf(null, null, null));
    }
}
