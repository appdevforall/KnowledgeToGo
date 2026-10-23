package org.appdevforall.k2go.contentexchange.domain;

/**
 * One item read from a bundle's {@code manifest.json} (or recovered by scanning the drive when no
 * manifest is present). The address and display fields the review UI needs, the archive file name on
 * the drive to read, and the raw manifest-entry JSON text that is handed back verbatim to the core
 * ({@code /v1/import/validate} and {@code /v1/import/item}).
 *
 * <p>Immutable value object. The JSON stays a {@link String} so this pure domain type carries no JSON
 * framework; the data layer parses it and the core consumes it unchanged (identity, dedup, integrity,
 * requires are preserved).
 */
public final class ImportEntry {

    private final String app;
    private final String localRef;
    private final String title;
    private final long bytes;
    private final String payloadName;
    private final String entryJson;

    public ImportEntry(String app, String localRef, String title, long bytes,
                       String payloadName, String entryJson) {
        this.app = app;
        this.localRef = localRef;
        this.title = title;
        this.bytes = bytes;
        this.payloadName = payloadName;
        this.entryJson = entryJson;
    }

    /** Adapter key: kiwix, kolibri, calibre. The grouping axis and the import target. */
    public String app() {
        return app;
    }

    /** The app's own handle to this item (from {@code identity.localRef}). */
    public String localRef() {
        return localRef;
    }

    public String title() {
        return title;
    }

    /** Declared size from the manifest; {@code <= 0} when unknown. Progress weight + preflight input. */
    public long bytes() {
        return bytes;
    }

    /** The archive file name to read from the drive ({@code payload} in the manifest entry). */
    public String payloadName() {
        return payloadName;
    }

    /** The raw manifest-entry JSON, passed to the core unchanged (it owns the shape). */
    public String entryJson() {
        return entryJson;
    }

    public ItemRef ref() {
        return new ItemRef(app, localRef);
    }
}
