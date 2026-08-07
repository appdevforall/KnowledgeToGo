/*
 * ============================================================================
 * Name        : GetChannelCatalogUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Business rules for turning the bundled catalog into the list the
 *               picker shows. Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves the channel list for a given filter.
 *
 * <p>Three rules, all of them things the UI would otherwise get wrong:
 *
 * <ul>
 *   <li><b>De-duplicate by channel id.</b> The generator already collapses
 *       duplicates to the highest published version, so this is a second line of
 *       defence rather than the primary one — it also covers a hand-edited asset
 *       and any future source that has not been through the generator. Two
 *       identical-looking rows in a picker is a bug the user notices.</li>
 *   <li><b>Apply the filter here, not in the view.</b> Same rule for every
 *       caller, and testable without a screen.</li>
 *   <li><b>Report how old the catalog is.</b> It ships in the APK and goes stale
 *       between releases; a screen that cannot say so is lying by omission.</li>
 * </ul>
 *
 * <p>Note what this deliberately does <em>not</em> do: there is no live-versus-
 * fallback rule, because the catalog has one source. Studio's channel endpoint
 * is 97 % base64 thumbnails with no way to opt out (ADR-4954 D1), so it is not
 * fetched at runtime at all. If a background refresh is ever added, the fallback
 * rule gets written then, against a real second source.
 */
public final class GetChannelCatalogUseCase {

    /** The filtered channels plus the catalog's age. Immutable. */
    public static final class Result {
        private final List<Channel> channels;
        private final String generatedOn;

        Result(List<Channel> channels, String generatedOn) {
            this.channels = channels;
            this.generatedOn = generatedOn;
        }

        /** Matching channels, in catalog order. Unmodifiable, never null. */
        public List<Channel> channels() {
            return channels;
        }

        /** ISO-8601 date the catalog was generated, or empty when unstamped. */
        public String generatedOn() {
            return generatedOn;
        }

        public boolean hasGeneratedOn() {
            return !generatedOn.isEmpty();
        }

        public boolean isEmpty() {
            return channels.isEmpty();
        }

        public int size() {
            return channels.size();
        }
    }

    private final CatalogRepository repository;

    public GetChannelCatalogUseCase(CatalogRepository repository) {
        this.repository = repository;
    }

    /** Every channel, unfiltered. */
    public Result execute() {
        return execute(CatalogQuery.all());
    }

    /** The channels matching {@code query}. */
    public Result execute(CatalogQuery query) {
        CatalogQuery q = query == null ? CatalogQuery.all() : query;

        LinkedHashMap<String, Channel> byId = new LinkedHashMap<>();
        List<Channel> all = repository.channels();
        if (all != null) {
            for (Channel c : all) {
                if (c != null && q.matches(c) && !byId.containsKey(c.id())) {
                    byId.put(c.id(), c);
                }
            }
        }

        String on = repository.catalogGeneratedOn();
        return new Result(
                Collections.unmodifiableList(new ArrayList<>(byId.values())),
                on == null ? "" : on.trim());
    }

    /**
     * The language codes present in the catalog, in first-appearance order.
     *
     * <p>Derived rather than hardcoded, unlike {@code KiwixCategories.ALL}: the
     * set of languages a channel exists in changes upstream between releases,
     * and a fixed list would offer filters that match nothing.
     */
    public List<String> availableLanguages() {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        List<Channel> all = repository.channels();
        if (all != null) {
            for (Channel c : all) {
                if (c != null && !c.langCode().isEmpty()) {
                    codes.add(c.langCode());
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(codes));
    }

    /**
     * Language code to the name Studio publishes for it, e.g. {@code es} to
     * {@code Español}.
     *
     * <p>Taken from the catalog rather than derived from {@code Locale}: these are
     * the names the content's own publishers chose, in the language itself, and
     * they cover codes a JVM locale renders as the bare code — {@code mul} for
     * multilingual channels being the obvious one. Codes with no name fall back to
     * themselves so a caller never has to handle a null.
     */
    public Map<String, String> languageNames() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        List<Channel> all = repository.channels();
        if (all != null) {
            for (Channel c : all) {
                if (c == null || c.langCode().isEmpty()) {
                    continue;
                }
                String existing = names.get(c.langCode());
                if (existing == null || existing.isEmpty()) {
                    names.put(c.langCode(),
                            c.langName().isEmpty() ? c.langCode() : c.langName());
                }
            }
        }
        return Collections.unmodifiableMap(names);
    }
}
