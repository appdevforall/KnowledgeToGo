package org.appdevforall.k2go.contentexchange.domain;

/**
 * Bytes used by one app's content, as reported by the core {@code GET /v1/storage}
 * {@code perApp} list. Immutable value object. Pure domain type.
 */
public final class AppUsage {

    private final String app;
    private final long bytes;

    public AppUsage(String app, long bytes) {
        this.app = app;
        this.bytes = bytes;
    }

    public String app() {
        return app;
    }

    public long bytes() {
        return bytes;
    }
}
