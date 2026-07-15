package org.iiab.controller.portal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure-JVM tests for picking the best pdf.js build for a WebView version. */
public class PdfViewerRouterTest {

    private static final PdfViewerBuild V6 =
            new PdfViewerBuild("6", "/pdfjs/6/web/viewer.html", 118);
    private static final PdfViewerBuild V4 =
            new PdfViewerBuild("4", "/pdfjs/4/web/viewer.html", 88);
    private static final List<PdfViewerBuild> BOTH = Arrays.asList(V6, V4);

    @Test public void modernWebViewPicksNewest() {
        assertEquals("6", PdfViewerRouter.pick(140, BOTH).getId());
    }

    @Test public void midWebViewPicksV4() {
        // 100 satisfies v4 (>=88) but not v6 (>=118).
        assertEquals("4", PdfViewerRouter.pick(100, BOTH).getId());
    }

    @Test public void tooOldReturnsNull() {
        assertNull(PdfViewerRouter.pick(80, BOTH));
    }

    @Test public void unknownVersionReturnsNull() {
        assertNull(PdfViewerRouter.pick(0, BOTH));
    }

    @Test public void noBuildsReturnsNull() {
        assertNull(PdfViewerRouter.pick(140, Collections.emptyList()));
        assertNull(PdfViewerRouter.pick(140, null));
    }

    @Test public void onlyV6Served_oldDeviceFallsBack() {
        // v4 removed by policy: a mid WebView no longer has a build -> null (caller downloads).
        List<PdfViewerBuild> onlyV6 = Collections.singletonList(V6);
        assertEquals("6", PdfViewerRouter.pick(120, onlyV6).getId());
        assertNull(PdfViewerRouter.pick(100, onlyV6));
    }
}
