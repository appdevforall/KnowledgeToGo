/*
 * ============================================================================
 * Name        : ModuleCards.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. Presentation catalog for Module management (Play Store-style cards).
 *               The roster (ModuleRegistry.MASTER_ROSTER) is the canonical list of proot modules;
 *               this adds the redesign UI metadata (title / subtitle / description / image) for the
 *               ones we present as cards, plus whether a module carries a content selector (only
 *               maps does). A module is SHOWN in the hub only if it has a card here AND is not
 *               already installed (probeAll). Adding/removing a module from the UI is just a card
 *               entry — e.g. Matomo in/out, or maps added when its selector flow lands.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import org.iiab.controller.ModuleRegistry;
import org.iiab.controller.R;

import java.util.ArrayList;
import java.util.List;

public final class ModuleCards {
    private ModuleCards() {}

    public static final class Card {
        public final ModuleRegistry.IiabModule module;   // canonical: yamlBaseKey, endpoint, requires64Bit
        public final int titleRes;
        public final int detailTitleRes;                 // title shown on the detail screen (may differ)
        public final int subRes;
        public final int descRes;
        public final int imageRes;
        public final boolean hasSelector;                // maps → content selector; others minimalist

        Card(String yamlKey, int titleRes, int detailTitleRes, int subRes, int descRes,
             int imageRes, boolean hasSelector) {
            this.module = find(yamlKey);
            this.titleRes = titleRes;
            this.detailTitleRes = detailTitleRes;
            this.subRes = subRes;
            this.descRes = descRes;
            this.imageRes = imageRes;
            this.hasSelector = hasSelector;
        }

        public String key() { return module.yamlBaseKey; }
        public String endpoint() { return module.endpoint; }
        public boolean requires64Bit() { return module.requires64Bit; }
    }

    // The modules we present as cards, in display order. maps is added when its selector flow lands
    // (PR2); dashboard is core (not a module). Every yamlKey here must exist in MASTER_ROSTER.
    private static final Card[] CATALOG = {
            new Card("kolibri",    R.string.k2go_mod_kolibri_title,    R.string.k2go_mod_kolibri_title,
                    R.string.k2go_mod_kolibri_sub,    R.string.k2go_mod_kolibri_desc,
                    R.drawable.k2go_module_placeholder, false),
            new Card("calibreweb", R.string.k2go_mod_calibreweb_title, R.string.k2go_mod_calibreweb_title,
                    R.string.k2go_mod_calibreweb_sub, R.string.k2go_mod_calibreweb_desc,
                    R.drawable.k2go_module_placeholder, false),
            new Card("kiwix",      R.string.k2go_mod_kiwix_title,      R.string.k2go_mod_kiwix_detail_title,
                    R.string.k2go_mod_kiwix_sub,      R.string.k2go_mod_kiwix_desc,
                    R.drawable.k2go_module_placeholder, false),
            new Card("code",       R.string.k2go_mod_code_title,       R.string.k2go_mod_code_title,
                    R.string.k2go_mod_code_sub,       R.string.k2go_mod_code_desc,
                    R.drawable.k2go_module_placeholder, false),
            new Card("matomo",     R.string.k2go_mod_matomo_title,     R.string.k2go_mod_matomo_title,
                    R.string.k2go_mod_matomo_sub,     R.string.k2go_mod_matomo_desc,
                    R.drawable.k2go_module_placeholder, false),
            new Card("maps",       R.string.k2go_mod_maps_title,       R.string.k2go_mod_maps_title,
                    R.string.k2go_mod_maps_sub,       R.string.k2go_mod_maps_desc,
                    R.drawable.k2go_module_placeholder, true),   // ADFA-4958: proot module WITH a content selector
    };

    /** All presentable module cards (roster-backed), in display order. */
    public static List<Card> all() {
        List<Card> out = new ArrayList<>();
        for (Card c : CATALOG) if (c.module != null) out.add(c);
        return out;
    }

    public static Card byKey(String yamlBaseKey) {
        for (Card c : CATALOG) if (c.module != null && c.module.yamlBaseKey.equals(yamlBaseKey)) return c;
        return null;
    }

    /** ADFA-4958: map a Home card's endpoint (e.g. "books") to its module card, or null if the
     *  endpoint is not a presentable module (e.g. "maps" is content, not a module). */
    public static Card byEndpoint(String endpoint) {
        if (endpoint == null) return null;
        for (Card c : CATALOG) if (c.module != null && endpoint.equals(c.module.endpoint)) return c;
        return null;
    }

    /** ADFA-4958: curated version (manual for now; sources differ per module). null -> no chip. */
    public static String version(String key) {
        if (key == null) return null;
        switch (key) {
            case "kolibri": return "0.19";
            default: return null;
        }
    }

    /** ADFA-4958: curated license (verified). */
    public static String license(String key) {
        if (key == null) return null;
        switch (key) {
            case "kolibri":    return "MIT";
            case "calibreweb": return "GPL-3.0";
            case "kiwix":      return "GPL-3.0";
            case "code":       return "MIT";
            case "matomo":     return "GPL-3.0";
            case "maps":       return "ODbL";
            default: return null;
        }
    }

    /** ADFA-4958: "What it includes" line — makes module-vs-content explicit. 0 -> hide. */
    public static int includesRes(String key) {
        if (key == null) return 0;
        switch (key) {
            case "kolibri":    return R.string.k2go_mod_kolibri_includes;
            case "calibreweb": return R.string.k2go_mod_calibreweb_includes;
            case "kiwix":      return R.string.k2go_mod_kiwix_includes;
            case "code":       return R.string.k2go_mod_code_includes;
            case "matomo":     return R.string.k2go_mod_matomo_includes;
            case "maps":       return R.string.k2go_mod_maps_includes;
            default: return 0;
        }
    }

    private static ModuleRegistry.IiabModule find(String yamlKey) {
        for (ModuleRegistry.IiabModule m : ModuleRegistry.MASTER_ROSTER) {
            if (m.yamlBaseKey.equals(yamlKey)) return m;
        }
        return null;
    }
}
