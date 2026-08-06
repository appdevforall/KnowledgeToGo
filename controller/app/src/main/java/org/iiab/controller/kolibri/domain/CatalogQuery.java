/*
 * ============================================================================
 * Name        : CatalogQuery.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : What the user is filtering the channel catalog by.
 *               Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The filter the picker applies to the channel catalog.
 *
 * <p>Only two axes, and that is not an oversight. Studio's public library
 * reports {@code categories: []} and {@code countries: []} on its labels
 * endpoint — the subject taxonomy exists in {@code le_utils} but is not
 * populated — so filtering by subject would filter against an empty index.
 * Language is richly populated (120+ entries) and keyword search works, so those
 * are the two axes that actually select anything.
 *
 * <p>Kept as a value object rather than loose parameters so adding an axis later
 * does not change the {@link CatalogRepository} signature.
 *
 * <p>Immutable.
 */
public final class CatalogQuery {

    private static final CatalogQuery ALL = new CatalogQuery("", Collections.<String>emptyList());

    private final String keyword;
    private final List<String> langCodes;

    private CatalogQuery(String keyword, List<String> langCodes) {
        this.keyword = keyword;
        this.langCodes = langCodes;
    }

    /** No filter: every public channel. */
    public static CatalogQuery all() {
        return ALL;
    }

    /**
     * Builds a query.
     *
     * @param keyword   free text matched against the channel name; null or blank
     *                  means no keyword
     * @param langCodes language codes to include; null or empty means every
     *                  language. Duplicates are dropped, order is preserved, and
     *                  codes are lowercased — Studio's own codes are lowercase
     *                  except for region suffixes ({@code pt-BR}), which it
     *                  matches case-insensitively.
     */
    public static CatalogQuery of(String keyword, List<String> langCodes) {
        String k = keyword == null ? "" : keyword.trim();
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (langCodes != null) {
            for (String c : langCodes) {
                if (c != null && !c.trim().isEmpty()) {
                    codes.add(c.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (k.isEmpty() && codes.isEmpty()) {
            return ALL;
        }
        return new CatalogQuery(k, Collections.unmodifiableList(new ArrayList<>(codes)));
    }

    /** Convenience for the common single-language case. */
    public static CatalogQuery ofLanguage(String langCode) {
        return of("", Collections.singletonList(langCode));
    }

    /** The keyword, or empty. Never null. */
    public String keyword() {
        return keyword;
    }

    public boolean hasKeyword() {
        return !keyword.isEmpty();
    }

    /** Language codes to include. Empty means all. Unmodifiable, never null. */
    public List<String> langCodes() {
        return langCodes;
    }

    public boolean hasLanguageFilter() {
        return !langCodes.isEmpty();
    }

    /** True when nothing is filtered out. */
    public boolean isUnfiltered() {
        return keyword.isEmpty() && langCodes.isEmpty();
    }

    /**
     * Whether a channel passes this filter. Used to apply the same rule to the
     * bundled catalog that Studio applies server-side, so the offline results
     * are the ones the user would have seen online.
     */
    public boolean matches(Channel c) {
        if (c == null) {
            return false;
        }
        if (hasLanguageFilter()
                && !langCodes.contains(c.langCode().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (hasKeyword()) {
            String needle = keyword.toLowerCase(Locale.ROOT);
            return c.name().toLowerCase(Locale.ROOT).contains(needle)
                    || c.description().toLowerCase(Locale.ROOT).contains(needle);
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CatalogQuery)) {
            return false;
        }
        CatalogQuery other = (CatalogQuery) o;
        return keyword.equals(other.keyword) && langCodes.equals(other.langCodes);
    }

    @Override
    public int hashCode() {
        return keyword.hashCode() * 31 + langCodes.hashCode();
    }

    @Override
    public String toString() {
        return "CatalogQuery{'" + keyword + "' " + langCodes + "}";
    }
}
