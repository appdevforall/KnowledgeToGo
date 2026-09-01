/*
 * ============================================================================
 * Name        : KolibriCatalogUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable view state for the Courses picker (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.presentation;

import org.appdevforall.k2go.kolibri.domain.Channel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What the browse screen renders: the filtered channels, the languages it can
 * filter by, and how old the catalog is.
 *
 * <p>Mirrors {@code RootfsUiState}: the screen observes this instead of reading
 * an asset or formatting sizes itself.
 *
 * <p>Immutable.
 */
public final class KolibriCatalogUiState {

    private static final KolibriCatalogUiState LOADING =
            new KolibriCatalogUiState(true, Collections.<Channel>emptyList(),
                    Collections.<String>emptyList(), Collections.<String, String>emptyMap(),
                    "", null);

    private final boolean loading;
    private final List<Channel> channels;
    private final List<String> languages;
    private final Map<String, String> languageNames;
    private final String generatedOn;
    private final String error;

    private KolibriCatalogUiState(boolean loading, List<Channel> channels,
                                  List<String> languages, Map<String, String> languageNames,
                                  String generatedOn, String error) {
        this.loading = loading;
        this.channels = channels;
        this.languages = languages;
        this.languageNames = languageNames;
        this.generatedOn = generatedOn;
        this.error = error;
    }

    public static KolibriCatalogUiState loading() {
        return LOADING;
    }

    static KolibriCatalogUiState ready(List<Channel> channels, List<String> languages,
                                       Map<String, String> languageNames, String generatedOn) {
        return new KolibriCatalogUiState(false,
                Collections.unmodifiableList(channels),
                Collections.unmodifiableList(languages),
                Collections.unmodifiableMap(languageNames),
                generatedOn == null ? "" : generatedOn, null);
    }

    static KolibriCatalogUiState error(String message) {
        return new KolibriCatalogUiState(false, Collections.<Channel>emptyList(),
                Collections.<String>emptyList(), Collections.<String, String>emptyMap(), "",
                message == null ? "" : message);
    }

    /**
     * The name Studio publishes for a language code, in that language. Falls back
     * to the code, so a caller never has to handle a null.
     */
    public String languageName(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        String n = languageNames.get(code);
        return n == null || n.isEmpty() ? code : n;
    }

    public boolean isLoading() {
        return loading;
    }

    /** True when the catalog was read but nothing matched the current filter. */
    public boolean isEmptyResult() {
        return !loading && error == null && channels.isEmpty();
    }

    public boolean hasError() {
        return error != null;
    }

    public String error() {
        return error == null ? "" : error;
    }

    /** Channels matching the filter, in catalog order. Unmodifiable. */
    public List<Channel> channels() {
        return channels;
    }

    /**
     * Language codes present in the whole catalog — not just in the current
     * result, or the filter would erase its own options as soon as it was used.
     */
    public List<String> languages() {
        return languages;
    }

    /** ISO date the bundled catalog was generated, or empty. */
    public String generatedOn() {
        return generatedOn;
    }
}
