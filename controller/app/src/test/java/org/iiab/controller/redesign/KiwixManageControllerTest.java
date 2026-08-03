package org.iiab.controller.redesign;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-JVM unit tests for the static URL matcher in {@link KiwixManageController} (ADFA-5004).
 * isKiwixPage is plain string parsing (no Android deps) — it gates the in-WebView ZIM manager to the
 * box's /kiwix/ pages, mirroring FqrController.isMapsPage.
 */
public class KiwixManageControllerTest {

    @Test
    public void isKiwixPage_matchesOnlyTheKiwixPaths() {
        assertTrue(KiwixManageController.isKiwixPage("http://localhost:8085/kiwix/"));
        assertTrue(KiwixManageController.isKiwixPage("http://localhost:8085/kiwix"));
        assertTrue(KiwixManageController.isKiwixPage("http://localhost:8085/kiwix/viewer#wikipedia"));
        assertTrue(KiwixManageController.isKiwixPage("http://localhost:8085/kiwix/?lang=en"));
        assertTrue(KiwixManageController.isKiwixPage("http://localhost:8085/kiwix/content/foo"));
        assertFalse(KiwixManageController.isKiwixPage("http://localhost:8085/maps/"));
        assertFalse(KiwixManageController.isKiwixPage("http://localhost:8085/home/"));
        assertFalse(KiwixManageController.isKiwixPage("http://localhost:8085/kiwixx/"));   // not a prefix boundary
        assertFalse(KiwixManageController.isKiwixPage(null));
    }
}
