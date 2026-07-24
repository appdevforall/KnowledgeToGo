/*
 * ============================================================================
 * Name        : KiwixCategories.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. The Kiwix ZIM catalog's content categories (project dirs on the
 *               mirror), with display names/subtitles. The set is fixed (matches the mirror's
 *               /zim/ project directories); per-category counts and sizes are data-driven from
 *               KiwixCatalog at runtime. Ordering in the UI is by count (desc), not this list.
 * ============================================================================
 */
package org.iiab.controller.redesign;

public final class KiwixCategories {
    private KiwixCategories() {}

    public static final class Category {
        public final String key;       // mirror project dir AND filename prefix (e.g. "stack_exchange")
        public final String title;     // display name
        public final String subtitle;  // one-line description
        Category(String key, String title, String subtitle) {
            this.key = key; this.title = title; this.subtitle = subtitle;
        }
    }

    /** All 23 project directories under the mirror's /zim/. Empty ones (0 files for the chosen
     *  language) are shown disabled in the UI, never hidden. */
    public static final Category[] ALL = {
            new Category("wikipedia",      "Wikipedia",      "Encyclopedia"),
            new Category("ted",            "TED talks",      "Video talks"),
            new Category("devdocs",        "Dev docs",       "Programming"),
            new Category("videos",         "Videos",         "Educational video"),
            new Category("stack_exchange", "Stack Exchange", "Q&A"),
            new Category("other",          "Other",          "Mixed collections"),
            new Category("wikibooks",      "Wikibooks",      "Textbooks"),
            new Category("wiktionary",     "Wiktionary",     "Dictionary"),
            new Category("wikiquote",      "Wikiquote",      "Quotations"),
            new Category("wikisource",     "Wikisource",     "Source texts"),
            new Category("phet",           "PhET",           "Science sims"),
            new Category("gutenberg",      "Gutenberg",      "Books"),
            new Category("maps",           "Maps",           "Offline maps"),
            new Category("zimit",          "Zimit",          "Archived sites"),
            new Category("wikivoyage",     "Wikivoyage",     "Travel guides"),
            new Category("freecodecamp",   "freeCodeCamp",   "Learn to code"),
            new Category("wikiversity",    "Wikiversity",    "Learning"),
            new Category("vikidia",        "Vikidia",        "Kids' encyclopedia"),
            new Category("ifixit",         "iFixit",         "Repair guides"),
            new Category("libretexts",     "LibreTexts",     "Textbooks"),
            new Category("mooc",           "MOOC",           "Courses"),
            new Category("psiram",         "Psiram",         "Skeptic wiki"),
            new Category("wikinews",       "Wikinews",       "News"),
    };

    public static Category byKey(String key) {
        for (Category c : ALL) if (c.key.equals(key)) return c;
        return null;
    }
}
