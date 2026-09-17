package org.appdevforall.k2go.util;

/**
 * The single source for turning a URL into its path by pure string parsing -- no
 * {@code android.net.Uri}, so it is dependency-free and unit-testable.
 *
 * <p>Strips the {@code #fragment}, the {@code ?query} and the {@code scheme://host},
 * returning the path. Returns {@code "/"} when a scheme is present but there is no
 * path, and {@code ""} for a null URL. The in-WebView controllers (FQR maps, Kiwix
 * manage, Kolibri guard -- K2GO-395) all use this to decide "is this the box's
 * /maps/ , /kiwix/ or /kolibri/ page", instead of each carrying its own copy.
 */
public final class WebPath {

    private WebPath() {}

    public static String pathOf(String url) {
        if (url == null) return "";
        String u = url;
        int hash = u.indexOf('#'); if (hash >= 0) u = u.substring(0, hash);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int scheme = u.indexOf("://");
        if (scheme >= 0) { int slash = u.indexOf('/', scheme + 3); u = slash >= 0 ? u.substring(slash) : "/"; }
        return u;
    }
}
