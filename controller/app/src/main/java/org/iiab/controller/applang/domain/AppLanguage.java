package org.iiab.controller.applang.domain;

/**
 * One selectable UI language for the app. Pure value type (no Android).
 *
 * <p>{@code tag} is a BCP-47 language tag (e.g. {@code "es"}, {@code "ru-RU"}); an empty
 * tag means "follow the system / phone language" (the default behaviour). {@code label}
 * is the endonym shown in the selector (its own native name, the same in every locale).
 */
public final class AppLanguage {

    private final String tag;
    private final String label;
    private final String searchName;

    public AppLanguage(String tag, String label) {
        this(tag, label, label);
    }

    /** ADFA-4797: {@code searchName} is extra text a search box can match (e.g. the
     *  English name) but that is never shown; defaults to {@code label}. */
    public AppLanguage(String tag, String label, String searchName) {
        this.tag = tag == null ? "" : tag;
        this.label = label;
        this.searchName = searchName == null ? label : searchName;
    }

    public String tag() {
        return tag;
    }

    public String searchName() {
        return searchName;
    }

    public boolean isSystemDefault() {
        return tag.isEmpty();
    }

    @Override
    public String toString() {
        return label;
    }
}
