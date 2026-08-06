package org.iiab.controller.redesign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/** Pure-JVM tests for the ZIM selection resolver (ADFA-5042). The download id must be
 *  "&lt;project&gt;/&lt;file&gt;" — the contract dash-node relies on to build the mirror URL
 *  (/zim/&lt;project&gt;/&lt;file&gt;). Uses the real org.json on the test classpath; no Android deps. */
public class ZimSelectionTest {

    /** Catalog shape consumed by KiwixCatalog.langData: catalog[project][lang][entryKey] = entry. */
    private static JSONObject catalog() throws Exception {
        JSONObject zimitEntry = new JSONObject()
                .put("file", "canadian_prepper_bugoutconcepts_en_2026-02.zim")
                .put("creator", "canadian_prepper")
                .put("flavour", "all")
                .put("size", 512L);
        JSONObject wikiEntry = new JSONObject()
                .put("file", "wikipedia_en_all_maxi_2026-02.zim")
                .put("size", 38_000_000_000L);
        JSONObject c = new JSONObject();
        c.put("zimit", new JSONObject().put("en", new JSONObject().put("e1", zimitEntry)));
        c.put("wikipedia", new JSONObject().put("en", new JSONObject().put("w1", wikiEntry)));
        return c;
    }

    @Test public void buildsProjectSlashFileId() throws Exception {
        ZimSelection.Item it = ZimSelection.resolve(catalog(), "zimit|en|e1");
        assertNotNull(it);
        assertEquals("zimit/canadian_prepper_bugoutconcepts_en_2026-02.zim", it.id);
        assertEquals("zimit", it.project);
        assertEquals("canadian_prepper_bugoutconcepts_en_2026-02.zim", it.entry.optString("file"));
    }

    @Test public void wikipediaGetsItsOwnSubdir() throws Exception {
        ZimSelection.Item it = ZimSelection.resolve(catalog(), "wikipedia|en|w1");
        assertNotNull(it);
        assertEquals("wikipedia/wikipedia_en_all_maxi_2026-02.zim", it.id);
    }

    @Test public void nullCatalogReturnsNull() {
        assertNull(ZimSelection.resolve(null, "zimit|en|e1"));
    }

    @Test public void nullKeyReturnsNull() throws Exception {
        assertNull(ZimSelection.resolve(catalog(), null));
    }

    @Test public void tooFewPartsReturnsNull() throws Exception {
        assertNull(ZimSelection.resolve(catalog(), "zimit|en"));
    }

    @Test public void unknownLanguageReturnsNull() throws Exception {
        assertNull(ZimSelection.resolve(catalog(), "zimit|fr|e1"));
    }

    @Test public void unknownEntryReturnsNull() throws Exception {
        assertNull(ZimSelection.resolve(catalog(), "zimit|en|nope"));
    }
}
