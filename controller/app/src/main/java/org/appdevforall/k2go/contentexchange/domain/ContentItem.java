package org.appdevforall.k2go.contentexchange.domain;

/**
 * One managed content item on the box, as the manager UI lists it.
 *
 * <p>Immutable value object. Maps one entry of the core {@code GET /v1/content}
 * manifest (ADR "granular content exchange", Pillar 1 read side). This slice needs
 * only the display and selection fields: {@code app} (adapter key, the grouping
 * axis), {@code kind} (the app content type), {@code title}, {@code bytes}, and
 * {@code localRef} (the app's own authoritative-local handle to this exact item).
 * The pair {@code (app, localRef)} is the selection identity the later action
 * slices (export / delete) address an item by.
 *
 * <p>The cross-box {@code dedup} envelope the manifest also carries is NOT modeled
 * here: no code in this read/select slice reads it. The import / share slices that
 * need it extend this entity and its mapper.
 *
 * <p>Pure domain type: no Android and no networking dependencies.
 */
public final class ContentItem {

    private final String app;
    private final String kind;
    private final String title;
    private final long bytes;
    private final String localRef;

    public ContentItem(String app, String kind, String title, long bytes, String localRef) {
        this.app = app;
        this.kind = kind;
        this.title = title;
        this.bytes = bytes;
        this.localRef = localRef;
    }

    /** Adapter key: kiwix, kolibri, calibre, ... . The grouping axis. */
    public String app() {
        return app;
    }

    /** App content type (free-form, app-defined). */
    public String kind() {
        return kind;
    }

    public String title() {
        return title;
    }

    /** Available size in bytes; a value {@code <= 0} means "unknown". */
    public long bytes() {
        return bytes;
    }

    /** The app's own handle to this item on this box (export / delete address). */
    public String localRef() {
        return localRef;
    }

    /** Stable selection key for multi-select: {@code app + "/" + localRef}. */
    public String selectionId() {
        return app + "/" + localRef;
    }

    @Override
    public String toString() {
        return "ContentItem{" + app + ", " + localRef + ", bytes=" + bytes + '}';
    }
}
