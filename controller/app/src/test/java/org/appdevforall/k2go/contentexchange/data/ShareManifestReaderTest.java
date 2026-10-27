package org.appdevforall.k2go.contentexchange.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Share manifest parse (K2GO-376): the gate manifest item is the read-side item JSON plus the itemRef
 * and delivered annotations. The reader maps itemRef onto the payload handle and strips the annotations
 * from the entry handed to the importer, so network ingest matches the disk path. Real org.json.
 */
public class ShareManifestReaderTest {

    /** A share-manifest item: model.ManifestItem.to_json() shape plus itemRef + delivered. */
    private static JSONObject item(String app, String localRef, long bytes, String itemRef,
                                   boolean delivered) throws Exception {
        JSONObject identity = new JSONObject().put("localRef", localRef)
                .put("dedup", new JSONObject().put("line", "l").put("version", "v").put("idKind", "SOFT"));
        return new JSONObject().put("app", app).put("kind", "zim").put("title", "T " + localRef)
                .put("bytes", bytes).put("identity", identity).put("integrity", JSONObject.NULL)
                .put("payload", JSONObject.NULL).put("itemRef", itemRef).put("delivered", delivered);
    }

    private static byte[] manifest(JSONObject... items) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject it : items) {
            arr.put(it);
        }
        return new JSONObject().put("sessionId", "abc").put("items", arr)
                .put("missing", new JSONArray()).put("totalBytes", 0)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void mapsItemRefToPayloadAndKeepsFields() throws Exception {
        List<ImportEntry> out = ShareManifestReader.parse(manifest(
                item("kiwix", "a.zim", 10L, "0", false),
                item("kolibri", "chan", 20L, "1", true)));
        assertEquals(2, out.size());
        assertEquals("kiwix", out.get(0).app());
        assertEquals("a.zim", out.get(0).localRef());
        assertEquals("0", out.get(0).payloadName());   // the fetch handle is the itemRef
        assertEquals(10L, out.get(0).bytes());
        assertEquals("1", out.get(1).payloadName());
    }

    @Test
    public void entryJsonDropsShareAnnotations() throws Exception {
        List<ImportEntry> out = ShareManifestReader.parse(manifest(item("kiwix", "a.zim", 10L, "0", true)));
        JSONObject entry = new JSONObject(out.get(0).entryJson());
        assertFalse(entry.has("itemRef"));
        assertFalse(entry.has("delivered"));
        // The identity the importer needs is preserved verbatim.
        assertEquals("a.zim", entry.getJSONObject("identity").getString("localRef"));
    }

    @Test
    public void skipsNonNumericOrMissingHandles() throws Exception {
        assertTrue(ShareManifestReader.parse(manifest(item("kiwix", "a.zim", 10L, "x", false))).isEmpty());
        assertTrue(ShareManifestReader.parse(manifest(item("kiwix", "", 10L, "0", false))).isEmpty());
        assertTrue(ShareManifestReader.parse(manifest(item("", "a.zim", 10L, "0", false))).isEmpty());
    }

    @Test
    public void emptyOrNoItemsYieldsNoEntries() throws Exception {
        assertTrue(ShareManifestReader.parse(new byte[0]).isEmpty());
        assertTrue(ShareManifestReader.parse(null).isEmpty());
        byte[] noItems = new JSONObject().put("sessionId", "abc").toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(ShareManifestReader.parse(noItems).isEmpty());
    }
}
