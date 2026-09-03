/*
 * ============================================================================
 * Name        : CatalogManifest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Immutable description of a published
 *               catalog, parsed by the data layer from the manifest JSON the
 *               weekly workflow uploads to Cloudflare. Pure domain: no Android,
 *               no JSON, no I/O.
 * ============================================================================
 */
package org.appdevforall.k2go.catalog.domain;

import java.util.Collections;
import java.util.List;

public final class CatalogManifest {

    /** Per-item version — for a later per-item delta and the "a newer version exists" signal. */
    public static final class Item {
        public final String id;
        public final int version;

        public Item(String id, int version) {
            this.id = id;
            this.version = version;
        }
    }

    private final String catalog;
    private final String version;
    private final String generated;
    private final String hash;
    private final String url;
    private final List<Item> items;

    public CatalogManifest(String catalog, String version, String generated,
                           String hash, String url, List<Item> items) {
        this.catalog = catalog;
        this.version = version;
        this.generated = generated;
        this.hash = hash;
        this.url = url;
        this.items = items == null ? Collections.<Item>emptyList()
                : Collections.unmodifiableList(items);
    }

    public String catalog()   { return catalog; }
    public String version()   { return version; }
    public String generated() { return generated; }
    public String hash()      { return hash; }
    public String url()       { return url; }
    public List<Item> items() { return items; }
}
