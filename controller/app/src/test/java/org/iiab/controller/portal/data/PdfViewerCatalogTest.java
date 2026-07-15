package org.iiab.controller.portal.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.portal.domain.PdfViewerBuild;
import org.junit.Test;

import java.util.List;

/** Pure-JVM tests for parsing the pdf.js viewer manifest. */
public class PdfViewerCatalogTest {

    @Test public void parsesBuilds() throws Exception {
        String json = "{\"schema\":\"pdfjs-viewers-v1\",\"builds\":["
                + "{\"id\":\"6\",\"viewerPath\":\"/pdfjs/6/web/viewer.html\",\"minWebViewMajor\":118,\"version\":\"6.1.200\",\"tentative\":false},"
                + "{\"id\":\"4\",\"viewerPath\":\"/pdfjs/4/web/viewer.html\",\"minWebViewMajor\":88,\"version\":\"4.10.38\",\"tentative\":true}"
                + "]}";
        List<PdfViewerBuild> builds = PdfViewerCatalog.parse(json);
        assertEquals(2, builds.size());
        assertEquals("6", builds.get(0).getId());
        assertEquals(118, builds.get(0).getMinWebViewMajor());
        assertEquals("/pdfjs/4/web/viewer.html", builds.get(1).getViewerPath());
    }

    @Test public void skipsMalformedEntriesAndEmptyInput() throws Exception {
        assertTrue(PdfViewerCatalog.parse("").isEmpty());
        assertTrue(PdfViewerCatalog.parse(null).isEmpty());
        assertTrue(PdfViewerCatalog.parse("{}").isEmpty());
        // Missing viewerPath / minWebViewMajor -> skipped.
        String json = "{\"builds\":[{\"id\":\"x\"},{\"id\":\"6\",\"viewerPath\":\"/pdfjs/6/web/viewer.html\",\"minWebViewMajor\":118}]}";
        List<PdfViewerBuild> builds = PdfViewerCatalog.parse(json);
        assertEquals(1, builds.size());
        assertEquals("6", builds.get(0).getId());
    }
}
