/*
 * ============================================================================
 * Name        : MetalinkHttpOnly.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-588 (ADR-K2GO-554 Amendment 3). A Metalink with its <metaurl> elements
 *               removed, so aria2 downloads over HTTP only, never BitTorrent.
 * ============================================================================
 */
package org.appdevforall.k2go.download.domain;

import java.util.regex.Pattern;

/**
 * aria2 turns a Metalink that carries a {@code <metaurl mediatype="torrent">} into a BitTorrent
 * session, and {@code --follow-torrent=false} does not stop it (measured, ADR-K2GO-554 Amendment 3). So the
 * app gives aria2 a copy of the Metalink without the metaurl elements. The {@code <url>} mirrors,
 * size and hashes stay unchanged.
 */
public final class MetalinkHttpOnly {

    // A <metaurl ...>...</metaurl> element or a self-closing <metaurl .../>, with its leading space.
    // A namespace prefix (<ml:metaurl>) is allowed, as the Metalink parser accepts it.
    private static final Pattern METAURL = Pattern.compile(
            "[ \\t]*<(?:[\\w.-]+:)?metaurl\\b[^>]*?(?:/>|>.*?</(?:[\\w.-]+:)?metaurl\\s*>)[ \\t]*\\r?\\n?",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private MetalinkHttpOnly() {}

    /** The same Metalink text without any {@code <metaurl>} element. */
    public static String of(String metalinkXml) {
        if (metalinkXml == null) return null;
        return METAURL.matcher(metalinkXml).replaceAll("");
    }

    /** False when any metaurl text is left: the caller must not give that Metalink to aria2. */
    public static boolean isHttpOnly(String metalinkXml) {
        return metalinkXml != null && !metalinkXml.toLowerCase(java.util.Locale.ROOT).contains("metaurl");
    }
}
