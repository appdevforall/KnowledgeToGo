package org.iiab.controller.redesign;

import android.content.Context;
import java.util.Locale;
import java.util.Set;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;
import org.json.JSONObject;

/**
 * Colloquial, i18n-ready labels for the Kiwix Wikipedia variants (the "dictionary"). Coverage is
 * data-driven (all / top1m / top) — never a fixed dichotomy. All labels come from string
 * resources so a later l10n pass can translate them without touching this code.
 */
public final class WikiVariants {
    private WikiVariants() {}

    public static String coverageOf(String key) {
        if (key.startsWith("all_")) return "all";
        if (key.startsWith("top1m_")) return "top1m";
        return "top";
    }
    public static String detailOf(String key) {
        if (key.endsWith("_maxi")) return "maxi";
        if (key.endsWith("_mini")) return "mini";
        return "nopic";
    }

    public static String coverageName(Context c, String cov) {
        switch (cov) {
            case "all": return c.getString(R.string.k2go_wiki_cov_all);
            case "top1m": return c.getString(R.string.k2go_wiki_cov_top1m);
            default: return c.getString(R.string.k2go_wiki_cov_top);
        }
    }
    public static String coverageDesc(Context c, String cov) {
        switch (cov) {
            case "all": return c.getString(R.string.k2go_wiki_cov_all_desc);
            case "top1m": return c.getString(R.string.k2go_wiki_cov_top1m_desc);
            default: return c.getString(R.string.k2go_wiki_cov_top_desc);
        }
    }
    public static String detailName(Context c, String det) {
        switch (det) {
            case "maxi": return c.getString(R.string.k2go_wiki_det_maxi);
            case "mini": return c.getString(R.string.k2go_wiki_det_mini);
            default: return c.getString(R.string.k2go_wiki_det_nopic);
        }
    }
    public static String label(Context c, String key) {
        return coverageName(c, coverageOf(key)) + " · " + detailName(c, detailOf(key));
    }

    public static double sizeGb(JSONObject langData, String key) {
        if (langData == null) return -1;
        JSONObject o = langData.optJSONObject(key);
        return o == null ? -1 : o.optDouble("size", -1);
    }

    /** The single ZIM v1 installs from a multi-selection: first in canonical order. */
    public static String primary(Set<String> selected) {
        for (String k : InstallationPlanner.CANONICAL_VARIANTS) if (selected.contains(k)) return k;
        return null;
    }

    public static String gb(double s) {
        if (s < 0) return "—";
        if (s >= 1) return String.format(Locale.US, "%.1f GB", s);
        return Math.round(s * 1000) + " MB";
    }
}
