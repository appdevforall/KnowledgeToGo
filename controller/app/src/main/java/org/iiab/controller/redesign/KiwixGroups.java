/*
 * ============================================================================
 * Name        : KiwixGroups.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5033. Static theme grouping for the ZIM catalog category index. Maps each of the
 *               ~23 categories (KiwixCategories) to one of 5 groups, used by the browse screen's filter
 *               chips (short label) and the "See all" section headers (longer label). Pure client-side
 *               table shipped with the app; the same shape is meant to back other content types (e.g.
 *               Kolibri) later. A category not listed falls into "media" (the catch-all) so the grouped
 *               view is always complete. DRAFT map — under team review (ADFA-5033).
 *
 *               Template conventions (respect when copying this pattern to Kolibri/Books):
 *               controller/docs/CATALOG_BROWSE_TEMPLATE.md
 * ============================================================================
 */
package org.iiab.controller.redesign;

import org.iiab.controller.R;

import java.util.HashMap;
import java.util.Map;

public final class KiwixGroups {
    private KiwixGroups() {}

    /** A theme group: a short chip label and a longer section header, both string resources. */
    public static final class Group {
        public final String key;
        public final int chipLabel;     // short — used on the filter chip
        public final int headerLabel;   // longer — used as the "See all" section header
        Group(String key, int chipLabel, int headerLabel) {
            this.key = key; this.chipLabel = chipLabel; this.headerLabel = headerLabel;
        }
    }

    /** Display + filter order (chips and See-all sections follow this). */
    public static final Group[] ALL = {
            new Group("reference", R.string.k2go_zim_grp_reference, R.string.k2go_zim_grp_reference_hdr),
            new Group("learning",  R.string.k2go_zim_grp_learning,  R.string.k2go_zim_grp_learning_hdr),
            new Group("tools",     R.string.k2go_zim_grp_tools,     R.string.k2go_zim_grp_tools_hdr),
            new Group("media",     R.string.k2go_zim_grp_media,     R.string.k2go_zim_grp_media_hdr),
            new Group("kids",      R.string.k2go_zim_grp_kids,      R.string.k2go_zim_grp_kids_hdr),
    };

    private static final String CATCH_ALL = "media";

    // category key -> group key. DRAFT (ADFA-5033); balance/placement pending team review.
    private static final Map<String, String> MAP = new HashMap<>();
    static {
        put("reference", "wikipedia", "wiktionary", "wikiquote", "wikisource", "wikinews", "psiram");
        put("learning",  "freecodecamp", "phet", "wikiversity", "wikibooks", "libretexts", "mooc");
        put("tools",     "devdocs", "stack_exchange", "ifixit");
        put("media",     "ted", "videos", "gutenberg", "zimit", "wikivoyage", "other", "maps");
        put("kids",      "vikidia");
    }

    private static void put(String group, String... catKeys) {
        for (String k : catKeys) MAP.put(k, group);
    }

    /** Group key for a category (catch-all "media" if unmapped, so grouping is always complete). */
    public static String groupOf(String categoryKey) {
        String g = MAP.get(categoryKey);
        return g != null ? g : CATCH_ALL;
    }

    /** Look up a group by key (null if unknown). */
    public static Group byKey(String groupKey) {
        for (Group g : ALL) if (g.key.equals(groupKey)) return g;
        return null;
    }
}
