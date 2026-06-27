package org.iiab.controller.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Unit tests for {@link MetalinkSplit} — the domain rule that scales aria2c's
 * {@code --split} by the number of HTTP mirrors in a Metalink (ADFA-4473).
 * Pure JVM, no Android dependencies.
 */
public class MetalinkSplitTest {

    private static InputStream xml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    // --- isMetalinkUrl ---

    @Test
    public void recognizesMetalinkExtensions() {
        assertTrue(MetalinkSplit.isMetalinkUrl("https://host/rootfs/latest.meta4"));
        assertTrue(MetalinkSplit.isMetalinkUrl("https://host/rootfs/latest.metalink"));
        assertTrue(MetalinkSplit.isMetalinkUrl("HTTPS://HOST/LATEST.META4")); // case-insensitive
    }

    @Test
    public void rejectsNonMetalinkUrls() {
        assertFalse(MetalinkSplit.isMetalinkUrl("https://host/rootfs.tar.zst"));
        assertFalse(MetalinkSplit.isMetalinkUrl("magnet:?xt=urn:btih:abc"));
        assertFalse(MetalinkSplit.isMetalinkUrl("https://host/file.torrent"));
        assertFalse(MetalinkSplit.isMetalinkUrl(null));
    }

    // --- isHttpMirror ---

    @Test
    public void countsOnlyHttpSchemes() {
        assertTrue(MetalinkSplit.isHttpMirror("http://a/x"));
        assertTrue(MetalinkSplit.isHttpMirror("HTTPS://A/X"));
        assertTrue(MetalinkSplit.isHttpMirror("  https://a/x  ")); // trimmed
        assertFalse(MetalinkSplit.isHttpMirror("ftp://a/x"));
        assertFalse(MetalinkSplit.isHttpMirror("magnet:?xt=urn:btih:abc"));
        assertFalse(MetalinkSplit.isHttpMirror(""));
        assertFalse(MetalinkSplit.isHttpMirror(null));
    }

    // --- splitForMirrorCount (clamp) ---

    @Test
    public void clampNeverDropsBelowBase() {
        assertEquals(MetalinkSplit.BASE_SPLIT, MetalinkSplit.splitForMirrorCount(0));
        assertEquals(MetalinkSplit.BASE_SPLIT, MetalinkSplit.splitForMirrorCount(1));
        assertEquals(MetalinkSplit.BASE_SPLIT, MetalinkSplit.splitForMirrorCount(-5));
    }

    @Test
    public void clampScalesFourPerMirror() {
        assertEquals(8, MetalinkSplit.splitForMirrorCount(2));
        assertEquals(12, MetalinkSplit.splitForMirrorCount(3));
        assertEquals(16, MetalinkSplit.splitForMirrorCount(4));
    }

    @Test
    public void clampNeverExceedsMax() {
        assertEquals(MetalinkSplit.MAX_SPLIT, MetalinkSplit.splitForMirrorCount(5));
        assertEquals(MetalinkSplit.MAX_SPLIT, MetalinkSplit.splitForMirrorCount(100));
    }

    // --- countHttpMirrors ---

    @Test
    public void countsHttpUrlMirrorsIgnoringOthers() throws Exception {
        String meta4 =
                "<?xml version='1.0' encoding='UTF-8'?>"
                        + "<metalink xmlns='urn:ietf:params:xml:ns:metalink'>"
                        + "  <file name='rootfs.tar.zst'>"
                        + "    <url>https://mirror1/rootfs.tar.zst</url>"
                        + "    <url>http://mirror2/rootfs.tar.zst</url>"
                        + "    <url>ftp://mirror3/rootfs.tar.zst</url>"        // ignored
                        + "    <metaurl mediatype='torrent'>https://t/x.torrent</metaurl>" // ignored
                        + "  </file>"
                        + "</metalink>";
        assertEquals(2, MetalinkSplit.countHttpMirrors(xml(meta4)));
    }

    @Test
    public void countsZeroWhenNoHttpMirrors() throws Exception {
        String meta4 =
                "<metalink><file name='f'>"
                        + "<metaurl>https://t/x.torrent</metaurl>"
                        + "</file></metalink>";
        assertEquals(0, MetalinkSplit.countHttpMirrors(xml(meta4)));
    }

    @Test
    public void malformedXmlThrowsSoCallerCanFallBack() {
        assertThrows(Exception.class,
                () -> MetalinkSplit.countHttpMirrors(xml("this is not xml <<<")));
    }

    // --- splitFromMetalink (end to end) ---

    @Test
    public void splitFromMetalinkCombinesCountAndClamp() throws Exception {
        String meta4 =
                "<metalink><file name='f'>"
                        + "<url>https://m1/f</url>"
                        + "<url>https://m2/f</url>"
                        + "<url>https://m3/f</url>"
                        + "</file></metalink>";
        // 3 http mirrors -> 4 * 3 = 12
        assertEquals(12, MetalinkSplit.splitFromMetalink(xml(meta4)));
    }
}
