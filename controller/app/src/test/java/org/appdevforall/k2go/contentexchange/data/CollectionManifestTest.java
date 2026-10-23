package org.appdevforall.k2go.contentexchange.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manifest-building tests. Uses the real org.json on the unit-test classpath
 * ({@code testImplementation 'org.json:json'}).
 */
public class CollectionManifestTest {

    private static JSONObject entry(String app, String localRef, long bytes) throws Exception {
        JSONObject dedup = new JSONObject()
                .put("line", "wikipedia_en")
                .put("build", "uuid-123")
                .put("version", "2026-01")
                .put("idKind", "authoritative");
        JSONObject identity = new JSONObject().put("localRef", localRef).put("dedup", dedup);
        return new JSONObject()
                .put("app", app)
                .put("kind", "zim")
                .put("title", "Title " + localRef)
                .put("bytes", bytes)
                .put("identity", identity)
                .put("integrity", JSONObject.NULL)
                .put("payload", JSONObject.NULL);
    }

    @Test
    public void buildStampsPayloadAndPreservesIdentity() throws Exception {
        ItemRef a = new ItemRef("kiwix", "a.zim");
        ItemRef b = new ItemRef("kolibri", "chan");
        Map<ItemRef, JSONObject> entries = new LinkedHashMap<>();
        entries.put(a, entry("kiwix", "a.zim", 10L));
        entries.put(b, entry("kolibri", "chan", 20L));
        List<ItemRef> order = new ArrayList<>();
        order.add(a);
        order.add(b);
        Map<ItemRef, String> names = new LinkedHashMap<>();
        names.put(a, "a.zim");
        names.put(b, "chan.tar");

        byte[] bytes = CollectionManifest.build(entries, order, names);
        JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));

        assertEquals(CollectionManifest.FORMAT, manifest.getString("format"));
        assertTrue(manifest.has("generated"));
        assertEquals("k2go", manifest.getJSONObject("source").getString("env"));

        assertEquals(2, manifest.getJSONArray("items").length());
        JSONObject first = manifest.getJSONArray("items").getJSONObject(0);
        assertEquals("a.zim", first.getString("payload"));                 // stamped
        assertEquals("kiwix", first.getString("app"));                     // preserved
        assertEquals("a.zim", first.getJSONObject("identity").getString("localRef"));
        assertEquals("authoritative",
                first.getJSONObject("identity").getJSONObject("dedup").getString("idKind"));
        JSONObject second = manifest.getJSONArray("items").getJSONObject(1);
        assertEquals("chan.tar", second.getString("payload"));
    }

    @Test
    public void buildSkipsItemsWithNoCapturedEntry() throws Exception {
        ItemRef a = new ItemRef("kiwix", "a.zim");
        ItemRef missing = new ItemRef("kiwix", "gone");
        Map<ItemRef, JSONObject> entries = new LinkedHashMap<>();
        entries.put(a, entry("kiwix", "a.zim", 10L));
        List<ItemRef> order = new ArrayList<>();
        order.add(a);
        order.add(missing);   // no captured entry: must be skipped, not crash
        Map<ItemRef, String> names = new LinkedHashMap<>();
        names.put(a, "a.zim");

        JSONObject manifest = new JSONObject(new String(
                CollectionManifest.build(entries, order, names), StandardCharsets.UTF_8));
        assertEquals(1, manifest.getJSONArray("items").length());
    }
}
