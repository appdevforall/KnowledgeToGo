/*
 * ============================================================================
 * Name        : CatalogRefreshStore.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Per-catalog refresh state: last check
 *               time, the active catalog's hash/generated date, and the cached
 *               ETag. Prefs-backed; keys namespaced by catalog name so one store
 *               serves Kolibri, Kiwix, etc.
 * ============================================================================
 */
package org.iiab.controller.catalog.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class CatalogRefreshStore {

    private static final String PREFS = "iiab_catalog_refresh";

    private final SharedPreferences prefs;

    public CatalogRefreshStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String catalog, String field) {
        return catalog + "." + field;
    }

    /** Epoch millis of the last update check (0 if never). */
    public long lastCheckMs(String catalog) {
        return prefs.getLong(key(catalog, "last_check"), 0L);
    }

    /** Hash of the catalog currently in use (empty until a pull is applied). */
    public String activeHash(String catalog) {
        return prefs.getString(key(catalog, "hash"), "");
    }

    /** Generated date of the catalog currently in use (empty until a pull is applied). */
    public String activeGenerated(String catalog) {
        return prefs.getString(key(catalog, "generated"), "");
    }

    /** ETag of the last fetched manifest, for the conditional GET (empty if none). */
    public String etag(String catalog) {
        return prefs.getString(key(catalog, "etag"), "");
    }

    /** Record that a check happened now (whether or not it changed anything). */
    public void recordChecked(String catalog, long nowMs) {
        prefs.edit().putLong(key(catalog, "last_check"), nowMs).apply();
    }

    /** Record a successful swap: the new hash/generated and the manifest ETag, and the check time. */
    public void recordApplied(String catalog, String hash, String generated, String etag, long nowMs) {
        prefs.edit()
                .putString(key(catalog, "hash"), hash == null ? "" : hash)
                .putString(key(catalog, "generated"), generated == null ? "" : generated)
                .putString(key(catalog, "etag"), etag == null ? "" : etag)
                .putLong(key(catalog, "last_check"), nowMs)
                .apply();
    }
}
