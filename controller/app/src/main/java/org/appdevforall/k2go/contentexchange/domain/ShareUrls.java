/*
 * ============================================================================
 * Name        : ShareUrls.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. Pure composition of the receiver's Share URLs from the scanned invite: the
 *               manifest, per-item fetch, and complete endpoints on the sender's gate. The invite's
 *               base origin and manifest path are already validated (ShareInviteValidator); this
 *               derives the sibling paths from the one manifest path, so there is a single source for
 *               the share-session path prefix. No android.*, no java.net, no JSON: unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

/**
 * Builds the receiver-side Share URLs from a validated invite. The invite carries the sender's origin
 * ({@code http://host:port}) and the manifest path ({@code /v1/share/<id>/manifest}); the fetch and
 * complete endpoints are siblings of the manifest ({@code .../fetch/<itemRef>}, {@code .../complete}),
 * so they are derived from the one manifest path rather than re-composed from a separately parsed
 * session id (one source for the prefix).
 *
 * <p>The token is never part of any URL: it travels in the {@code X-Share-Token} header. The
 * {@code itemRef} is the item's index in the sender's scoped set, always decimal digits (validated by
 * {@link #isValidItemRef}), so it needs no percent-encoding to be a safe single path segment.
 */
public final class ShareUrls {

    private static final String MANIFEST_SUFFIX = "/manifest";

    private ShareUrls() {
    }

    /** The manifest URL: {@code baseUrl + manifestPath}. */
    public static String manifest(String baseUrl, String manifestPath) {
        requireOrigin(baseUrl);
        return baseUrl + requireManifestPath(manifestPath);
    }

    /** The per-item fetch URL: the share-session prefix + {@code /fetch/<itemRef>}. */
    public static String fetch(String baseUrl, String manifestPath, String itemRef) {
        requireOrigin(baseUrl);
        if (!isValidItemRef(itemRef)) {
            throw new IllegalArgumentException("itemRef must be decimal digits");
        }
        return baseUrl + sessionPrefix(manifestPath) + "/fetch/" + itemRef;
    }

    /** The clean-success close URL: the share-session prefix + {@code /complete}. */
    public static String complete(String baseUrl, String manifestPath) {
        requireOrigin(baseUrl);
        return baseUrl + sessionPrefix(manifestPath) + "/complete";
    }

    /**
     * The share-session path prefix, the manifest path with its trailing {@code /manifest} removed
     * ({@code /v1/share/<id>/manifest} -> {@code /v1/share/<id>}). Fails closed when the path is not a
     * manifest path, so a drifted invite can never compose a sibling URL off a wrong base.
     */
    public static String sessionPrefix(String manifestPath) {
        String path = requireManifestPath(manifestPath);
        return path.substring(0, path.length() - MANIFEST_SUFFIX.length());
    }

    /** True when {@code itemRef} is a non-empty run of decimal digits (the gate's {@code str(index)}). */
    public static boolean isValidItemRef(String itemRef) {
        if (itemRef == null || itemRef.isEmpty() || itemRef.length() > 9) {
            return false;   // a 9-digit cap is far above any real item count and blocks absurd input
        }
        for (int i = 0; i < itemRef.length(); i++) {
            char c = itemRef.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static void requireOrigin(String baseUrl) {
        // A bare origin only: no trailing slash and no path, so the composed path is exactly ours.
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                || baseUrl.endsWith("/") || baseUrl.indexOf('/', baseUrl.indexOf("//") + 2) >= 0) {
            throw new IllegalArgumentException("baseUrl must be a bare http(s) origin");
        }
    }

    private static String requireManifestPath(String manifestPath) {
        if (manifestPath == null || manifestPath.length() <= MANIFEST_SUFFIX.length()
                || manifestPath.charAt(0) != '/' || !manifestPath.endsWith(MANIFEST_SUFFIX)
                || manifestPath.contains("..") || manifestPath.contains("://")) {
            throw new IllegalArgumentException("not a /.../manifest path");
        }
        return manifestPath;
    }
}
