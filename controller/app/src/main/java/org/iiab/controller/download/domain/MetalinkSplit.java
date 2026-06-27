/*
 * ============================================================================
 * Name        : MetalinkSplit.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule (ADFA-4473): derive aria2c --split from the number
 *               of HTTP mirrors advertised in a Metalink (.meta4 / .metalink).
 *               Pure counting + clamp logic; the network fetch stays in
 *               Aria2Manager.
 * ============================================================================
 */
package org.iiab.controller.download.domain;

import java.io.InputStream;
import java.util.Locale;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Pure (framework-free) helper that decides how many parallel pieces aria2c
 * should split a download into, based on how many HTTP(S) mirrors a Metalink
 * file lists.
 *
 * <p>Policy (ADFA-4473): per-server connections stay fixed at
 * {@link #CONNECTIONS_PER_MIRROR} (polite — never more than that to a single
 * mirror); total parallelism scales with the number of servers, so
 * {@code --split = CONNECTIONS_PER_MIRROR * N}, clamped to
 * [{@link #BASE_SPLIT}, {@link #MAX_SPLIT}]. Only http/https {@code <url>}
 * mirrors are counted; {@code <metaurl>} / torrent sources are ignored.
 *
 * <p>No {@code android.*}, no {@code java.net}, no HTTP here: it operates on an
 * already-fetched {@link InputStream} (the SAX parser is part of the JDK), so it
 * is unit-testable on a plain JVM. {@code Aria2Manager} owns the network fetch
 * and the fallback-to-{@link #BASE_SPLIT} behaviour on any download/parse error.
 */
public final class MetalinkSplit {

    /** Lower bound and fallback: aria2c's prior fixed value. */
    public static final int BASE_SPLIT = 4;
    /** Upper bound: never split more than this regardless of mirror count. */
    public static final int MAX_SPLIT = 16;
    /** Fixed per-server connection count (polite); also the per-mirror factor. */
    public static final int CONNECTIONS_PER_MIRROR = 4;

    private MetalinkSplit() {
        // Static utility; not instantiable.
    }

    /**
     * True if {@code url} points at a Metalink file by extension
     * ({@code .meta4} or {@code .metalink}). Anything else (direct URL, torrent)
     * is not a metalink and should fall back to {@link #BASE_SPLIT}.
     */
    public static boolean isMetalinkUrl(String url) {
        if (url == null) {
            return false;
        }
        String low = url.toLowerCase(Locale.US);
        return low.endsWith(".meta4") || low.endsWith(".metalink");
    }

    /**
     * True if {@code urlText} is an http/https mirror (the only kind counted).
     */
    public static boolean isHttpMirror(String urlText) {
        if (urlText == null) {
            return false;
        }
        String t = urlText.trim().toLowerCase(Locale.US);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /**
     * Clamp the split for a given http-mirror count:
     * {@code min(MAX_SPLIT, max(BASE_SPLIT, CONNECTIONS_PER_MIRROR * N))}.
     * A non-positive count is treated as a single mirror, so the result never
     * drops below {@link #BASE_SPLIT}.
     */
    public static int splitForMirrorCount(int httpMirrorCount) {
        int n = Math.max(1, httpMirrorCount);
        return Math.min(MAX_SPLIT, Math.max(BASE_SPLIT, CONNECTIONS_PER_MIRROR * n));
    }

    /**
     * Count the http/https {@code <url>} mirrors in a Metalink document.
     *
     * <p>Reads the whole stream with a namespace-unaware SAX parser that has
     * DOCTYPE declarations disabled (XXE guard). {@code <metaurl>} and any
     * non-http scheme are ignored.
     *
     * @throws Exception if the stream is not parseable as XML; callers fall back
     *                   to {@link #BASE_SPLIT}.
     */
    public static int countHttpMirrors(InputStream metalinkXml) throws Exception {
        final int[] count = {0};
        final boolean[] inUrl = {false};
        final StringBuilder sb = new StringBuilder();

        SAXParserFactory factory = SAXParserFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignore) {
            // Not all parsers expose this feature; the namespace-unaware parse
            // below still never resolves external entities for our counting use.
        }
        factory.setNamespaceAware(false);
        SAXParser parser = factory.newSAXParser();
        parser.parse(metalinkXml, new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes a) {
                if ("url".equalsIgnoreCase(qName)) {
                    inUrl[0] = true;
                    sb.setLength(0);
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inUrl[0]) {
                    sb.append(ch, start, length);
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("url".equalsIgnoreCase(qName)) {
                    inUrl[0] = false;
                    if (isHttpMirror(sb.toString())) {
                        count[0]++;
                    }
                }
            }
        });
        return count[0];
    }

    /**
     * Convenience: {@code splitForMirrorCount(countHttpMirrors(metalinkXml))}.
     *
     * @throws Exception if the stream is not parseable; callers fall back to
     *                   {@link #BASE_SPLIT}.
     */
    public static int splitFromMetalink(InputStream metalinkXml) throws Exception {
        return splitForMirrorCount(countHttpMirrors(metalinkXml));
    }
}
