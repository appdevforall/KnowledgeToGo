package org.appdevforall.k2go.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class MetalinkHttpOnlyTest {

    private static final String PUBLISHED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\" version=\"4.0\">\n"
            + "  <file name=\"k2go_2026.321_basic_c746edd_arm64-v8a.tar.gz\">\n"
            + "    <size>1590750919</size>\n"
            + "    <hash type=\"sha-256\">28173c20010e4bf54e3b276cb0dd68201e2e473422704bfae85b8ed7e24d3560</hash>\n"
            + "    <url priority=\"10\">https://example.org/k2go_2026.321_basic_c746edd_arm64-v8a.tar.gz</url>\n"
            + "    <metaurl priority=\"1\" mediatype=\"torrent\" name=\"k2go_2026.321_basic_c746edd_arm64-v8a.tar.gz\">k2go_2026.321_basic_c746edd_arm64-v8a.tar.gz.torrent</metaurl>\n"
            + "  </file>\n"
            + "</metalink>\n";

    @Test
    public void removesTheTorrentMetaurlAndKeepsEverythingElse() {
        String out = MetalinkHttpOnly.of(PUBLISHED);
        assertFalse(out.contains("metaurl"));
        assertFalse(out.contains(".torrent"));
        assertEquals(PUBLISHED.replaceAll("    <metaurl[^\\n]*\\n", ""), out);
    }

    @Test
    public void theResultStillParsesWithTheSameMirrorsSizeAndHash() throws Exception {
        MetalinkFile before = MetalinkFile.parse(
                new ByteArrayInputStream(PUBLISHED.getBytes(StandardCharsets.UTF_8)));
        MetalinkFile after = MetalinkFile.parse(new ByteArrayInputStream(
                MetalinkHttpOnly.of(PUBLISHED).getBytes(StandardCharsets.UTF_8)));
        assertEquals(before.fileName(), after.fileName());
        assertEquals(before.sizeBytes(), after.sizeBytes());
        assertEquals(before.sha256(), after.sha256());
        assertEquals(before.mirrors(), after.mirrors());
    }

    @Test
    public void removesSelfClosingAndMultilineMetaurls() {
        String in = "<file>\n<url>https://a/x</url>\n<metaurl mediatype=\"torrent\"/>\n"
                + "<METAURL mediatype=\"torrent\">\n  x.torrent\n</METAURL>\n</file>\n";
        assertEquals("<file>\n<url>https://a/x</url>\n</file>\n", MetalinkHttpOnly.of(in));
    }

    @Test
    public void aMetalinkWithoutMetaurlIsUnchanged() {
        String in = PUBLISHED.replaceAll("    <metaurl[^\\n]*\\n", "");
        assertEquals(in, MetalinkHttpOnly.of(in));
        assertTrue(MetalinkHttpOnly.of(in).contains("<url priority=\"10\">"));
    }

    @Test
    public void nullStaysNull() {
        assertNull(MetalinkHttpOnly.of(null));
    }

    @Test
    public void removesANamespacePrefixedMetaurl() {
        String in = "<ml:file name=\"a\">\n  <ml:metaurl mediatype=\"torrent\">x.torrent</ml:metaurl>\n"
                + "  <ml:url>https://m/a</ml:url>\n</ml:file>\n";
        String out = MetalinkHttpOnly.of(in);
        assertTrue(MetalinkHttpOnly.isHttpOnly(out));
        assertTrue(out.contains("<ml:url>https://m/a</ml:url>"));
    }

    @Test
    public void isHttpOnlyFailsClosed() {
        assertTrue(MetalinkHttpOnly.isHttpOnly(MetalinkHttpOnly.of(PUBLISHED)));
        assertFalse(MetalinkHttpOnly.isHttpOnly(PUBLISHED));
        assertFalse(MetalinkHttpOnly.isHttpOnly("<file>\n</METAURL>\n</file>"));
        assertFalse(MetalinkHttpOnly.isHttpOnly(null));
    }
}
