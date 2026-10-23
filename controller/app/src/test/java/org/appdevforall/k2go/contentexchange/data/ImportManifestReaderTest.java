package org.appdevforall.k2go.contentexchange.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.contentexchange.domain.ImportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ImportManifest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manifest parse + no-manifest scan. Uses the real org.json on the unit-test classpath.
 */
public class ImportManifestReaderTest {

    private static byte[] manifest(JSONObject... entries) throws Exception {
        JSONArray items = new JSONArray();
        for (JSONObject e : entries) {
            items.put(e);
        }
        return new JSONObject().put("format", "k2go-content-manifest/1").put("items", items)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject entry(String app, String localRef, String payload, long bytes) throws Exception {
        JSONObject identity = new JSONObject().put("localRef", localRef)
                .put("dedup", new JSONObject().put("line", "l").put("version", "v"));
        return new JSONObject().put("app", app).put("kind", "zim").put("title", "T " + localRef)
                .put("bytes", bytes).put("identity", identity).put("payload", payload);
    }

    @Test
    public void parsesEntriesAndKeepsRawJsonVerbatim() throws Exception {
        List<ImportEntry> out = ImportManifestReader.parse(
                manifest(entry("kiwix", "a.zim", "a.zim", 10L), entry("kolibri", "chan", "chan.tar", 20L)));
        assertEquals(2, out.size());
        assertEquals("kiwix", out.get(0).app());
        assertEquals("a.zim", out.get(0).payloadName());
        assertEquals(10L, out.get(0).bytes());
        // The raw entry round-trips (the core consumes it unchanged).
        JSONObject back = new JSONObject(out.get(0).entryJson());
        assertEquals("a.zim", back.getJSONObject("identity").getString("localRef"));
    }

    @Test
    public void skipsEntriesMissingAppLocalRefOrPayload() throws Exception {
        JSONObject noPayload = entry("kiwix", "a.zim", "", 10L);
        List<ImportEntry> out = ImportManifestReader.parse(manifest(noPayload));
        assertTrue(out.isEmpty());
    }

    @Test
    public void emptyOrNonItemsManifestYieldsNoEntries() throws Exception {
        assertTrue(ImportManifestReader.parse(new byte[0]).isEmpty());
        assertTrue(ImportManifestReader.parse("{}".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    public void scanRecoversKiwixAndFlagsTheRest() throws Exception {
        List<ImportCarrier.DriveFile> files = new ArrayList<>();
        files.add(new ImportCarrier.DriveFile("wikipedia_en_all_2023-10.zim", 123L));
        files.add(new ImportCarrier.DriveFile("channel.tar", 456L));
        files.add(new ImportCarrier.DriveFile("manifest.json", 1L));   // ignored by the scan

        ImportManifest m = ImportManifestReader.scan(files);
        assertFalse(m.hadManifest());
        assertEquals(1, m.entries().size());
        ImportEntry zim = m.entries().get(0);
        assertEquals("kiwix", zim.app());
        assertEquals("wikipedia_en_all_2023-10.zim", zim.localRef());
        assertEquals(123L, zim.bytes());
        // The synthesized entry carries dedup keys from the filename, idKind soft (no UUID read).
        JSONObject dedup = new JSONObject(zim.entryJson())
                .getJSONObject("identity").getJSONObject("dedup");
        assertEquals("wikipedia", dedup.getJSONObject("line").getString("creator"));
        assertEquals("2023-10", dedup.getString("version"));
        assertEquals("SOFT", dedup.getString("idKind"));

        assertEquals(1, m.unrecognized().size());
        assertEquals("channel.tar", m.unrecognized().get(0));
    }

    @Test
    public void parseKeepsASubfolderPayloadVerbatim() throws Exception {
        // A v2 manifest records "app/file" as the payload; the reader must keep it for openPayload.
        List<ImportEntry> out = ImportManifestReader.parse(
                manifest(entry("calibre", "book-7", "calibre/book-7.tar", 30L)));
        assertEquals(1, out.size());
        assertEquals("calibre/book-7.tar", out.get(0).payloadName());
        assertEquals("book-7", out.get(0).localRef());
    }

    @Test
    public void scanRecognizesAZimInsideAnAppSubfolder() throws Exception {
        // A v2 no-manifest bundle: the drive file arrives as a relative path. Recognize on the basename,
        // keep the full relative path as the payload, use the basename as the identity.
        List<ImportCarrier.DriveFile> files = new ArrayList<>();
        files.add(new ImportCarrier.DriveFile("kiwix/wikipedia_en_all_2023-10.zim", 123L));

        ImportManifest m = ImportManifestReader.scan(files);
        assertEquals(1, m.entries().size());
        ImportEntry zim = m.entries().get(0);
        assertEquals("kiwix", zim.app());
        assertEquals("wikipedia_en_all_2023-10.zim", zim.localRef());
        assertEquals("kiwix/wikipedia_en_all_2023-10.zim", zim.payloadName());
        JSONObject back = new JSONObject(zim.entryJson());
        assertEquals("kiwix/wikipedia_en_all_2023-10.zim", back.getString("payload"));
        assertEquals("wikipedia", back.getJSONObject("identity").getJSONObject("dedup")
                .getJSONObject("line").getString("creator"));
    }
}
