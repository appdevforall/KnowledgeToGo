/*
 * ============================================================================
 * Name        : ZimLandingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. "Wikipedia & ZIM content" landing (screen 1). Language selector
 *               (default = system content language), storage bar, search placeholder, and the
 *               browse-by-category list built from the live KiwixCatalog: all 23 categories,
 *               ordered by file count (desc), empty ones shown DISABLED/greyed (never hidden).
 *               Each category opens its detail screen. Catalog is loaded async (cache-first).
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.applang.data.ContentLanguage;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ZimLandingFragment extends Fragment {

    private static final int TOP_N = 6;   // ADFA-5033: categories shown by default before "See all"

    private JSONObject catalog;
    private long freeMb = 0, totalMb = 0;
    private LinearLayout cats;
    private LinearLayout chipRow;
    private TextView status, langLabel, langSub, storageLabel;
    private ProgressBar storageBar;
    private Button review;
    private View bottomBar;
    private String query = "";
    private boolean expanded = false;
    private String selectedGroup = null;   // ADFA-5033: null = "All"; otherwise a KiwixGroups key

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    private String lang() {
        if (getActivity() instanceof SetupLibraryActivity) {
            return ((SetupLibraryActivity) getActivity()).getZimLang();
        }
        return ContentLanguage.systemDefault();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_zim_landing, container, false);

        TextView back = root.findViewById(R.id.k2go_zim_back);
        back.setText("‹ " + getString(R.string.k2go_gm_hub_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        cats = root.findViewById(R.id.k2go_zim_cats);
        chipRow = root.findViewById(R.id.k2go_zim_chips);
        status = root.findViewById(R.id.k2go_zim_status);
        langLabel = root.findViewById(R.id.k2go_zim_lang);
        langSub = root.findViewById(R.id.k2go_zim_lang_sub);
        storageLabel = root.findViewById(R.id.k2go_zim_storage_label);
        storageBar = root.findViewById(R.id.k2go_zim_storage_bar);
        bottomBar = root.findViewById(R.id.k2go_zim_bottombar);
        review = root.findViewById(R.id.k2go_zim_review);
        review.setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) ((SetupLibraryActivity) getActivity()).openZimConfirm();
        });

        android.widget.EditText search = root.findViewById(R.id.k2go_zim_search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                if (catalog != null) buildRows();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        root.findViewById(R.id.k2go_zim_lang_box).setOnClickListener(v -> pickLanguage());

        // ADFA-5105: shared free-space probe instead of a per-screen StatFs copy.
        Long fb = org.appdevforall.k2go.storage.StorageProbe.freeBytes(requireContext());
        Long tb = org.appdevforall.k2go.storage.StorageProbe.totalBytes(requireContext());
        freeMb = (fb == null ? 0L : fb) / (1024L * 1024L);
        totalMb = (tb == null ? 0L : tb) / (1024L * 1024L);

        updateLangLabel();
        updateStorage();
        buildChips();
        status.setText(R.string.k2go_zim_loading);

        KiwixCatalog.getOrFetch(requireContext(), new KiwixCatalog.Listener() {
            @Override public void onReady(JSONObject c) {
                if (!isAdded()) return;
                catalog = c;
                status.setText("");
                buildRows();
            }
            @Override public void onError(String message) {
                if (!isAdded()) return;
                status.setText(getString(R.string.k2go_zim_unavailable));
            }
        });

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Selection may have changed in a category screen; refresh the storage line + counts.
        updateStorage();
        if (catalog != null) buildRows();
    }

    private void updateLangLabel() {
        // ADFA-5033: one-control selector — bold language + a muted source sub-line ("From system" /
        // "Manually selected"); the "· filters the catalog" clause is dropped (redundant).
        langLabel.setText(getString(R.string.k2go_zim_lang_fmt, langDisplay(lang())));
        boolean manual = (getActivity() instanceof SetupLibraryActivity)
                && ((SetupLibraryActivity) getActivity()).isZimLangManual();
        langSub.setText(manual ? R.string.k2go_zim_lang_state_manual : R.string.k2go_zim_lang_state_system);
    }

    private String langDisplay(String code) {
        if (KiwixCatalog.MUL.equals(code)) return getString(R.string.k2go_zim_lang_mul);
        try {
            Locale l = new Locale(code);
            String n = l.getDisplayName(l);
            if (n == null || n.isEmpty()) n = code;
            return n.substring(0, 1).toUpperCase(l) + n.substring(1);
        } catch (Exception e) { return code; }
    }

    private long selectionMb() {
        if (!(getActivity() instanceof SetupLibraryActivity)) return 0;
        long bytes = 0;
        for (Long b : ((SetupLibraryActivity) getActivity()).getZimCart().values()) bytes += b;
        return bytes / (1024L * 1024L);
    }

    private void updateStorage() {
        long sel = selectionMb();
        long used = Math.max(0, totalMb - freeMb);
        int pct = totalMb > 0 ? (int) Math.min(100, Math.round((used + sel) * 100.0 / totalMb)) : 0;
        storageBar.setProgress(pct);
        storageLabel.setText(getString(R.string.k2go_zim_storage_fmt, gb(used), gb(sel), gb(freeMb)));
        if (review != null) {
            // Always present; disabled at 0 MB until something is selected.
            review.setEnabled(sel > 0);
            review.setText(getString(R.string.k2go_zim_review_fmt, gb(sel)));
        }
    }

    private String gb(long mb) {   // ADFA-4910: one standard size formatter for the whole UI
        return org.appdevforall.k2go.util.ByteFormatter.humanMb(mb);
    }

    // ADFA-5033: "breathe" — show less by default (top-N + See all), group in See-all, filter by chip,
    // and collapse unavailable categories out of the flow instead of greying them in place.
    private void buildRows() {
        if (catalog == null) return;
        cats.removeAllViews();
        final String L = lang();

        // Search overrides everything: a flat list of matching categories across the whole catalog.
        if (!query.isEmpty()) {
            int shown = 0;
            for (KiwixCategories.Category c : availableSorted(L)) {
                if (!matches(c, query)) continue;
                cats.addView(categoryRow(c, countOf(c, L)));
                shown++;
            }
            status.setText(shown == 0 ? getString(R.string.k2go_zim_no_match) : "");
            return;
        }
        status.setText("");

        List<KiwixCategories.Category> available = availableSorted(L);
        int unavailable = KiwixCategories.ALL.length - available.size();

        // A group chip is selected: just that group's available categories (already filtered). Add the
        // header only once we know the group has at least one available category (no empty header).
        if (selectedGroup != null) {
            KiwixGroups.Group g = KiwixGroups.byKey(selectedGroup);
            boolean headerAdded = false;
            for (KiwixCategories.Category c : available) {
                if (!selectedGroup.equals(KiwixGroups.groupOf(c.key))) continue;
                if (!headerAdded && g != null) { cats.addView(sectionHeader(getString(g.headerLabel))); headerAdded = true; }
                cats.addView(categoryRow(c, countOf(c, L)));
            }
            // Nothing in this group is available in the current language → offer to change language.
            if (!headerAdded && unavailable > 0) cats.addView(unavailableLine(unavailable, L));
            return;
        }

        // "All": default = MOST CONTENT top-N + See all; expanded = grouped by theme.
        if (!expanded) {
            cats.addView(sectionHeader(getString(R.string.k2go_zim_most_content)));
            int top = Math.min(TOP_N, available.size());
            for (int i = 0; i < top; i++) cats.addView(categoryRow(available.get(i), countOf(available.get(i), L)));
            cats.addView(seeAllRow(KiwixCategories.ALL.length));
            if (unavailable > 0) cats.addView(unavailableLine(unavailable, L));
        } else {
            for (KiwixGroups.Group g : KiwixGroups.ALL) {
                boolean headerAdded = false;
                for (KiwixCategories.Category c : available) {
                    if (!g.key.equals(KiwixGroups.groupOf(c.key))) continue;
                    if (!headerAdded) { cats.addView(sectionHeader(getString(g.headerLabel))); headerAdded = true; }
                    cats.addView(categoryRow(c, countOf(c, L)));
                }
            }
            if (unavailable > 0) cats.addView(unavailableRow(unavailable, L));
        }
    }

    private int countOf(KiwixCategories.Category c, String L) { return KiwixCatalog.count(catalog, c.key, L); }

    /** Categories with content in the current language, most first. Unavailable ones drop out here. */
    private List<KiwixCategories.Category> availableSorted(String L) {
        List<KiwixCategories.Category> list = new ArrayList<>();
        for (KiwixCategories.Category c : KiwixCategories.ALL) if (countOf(c, L) > 0) list.add(c);
        Collections.sort(list, (a, b) -> Integer.compare(countOf(b, L), countOf(a, L)));
        return list;
    }

    // ---- chips (one horizontally-scrollable line; never wraps) -----------------------------------

    private void buildChips() {
        if (chipRow == null) return;
        chipRow.removeAllViews();
        chipRow.addView(chip(getString(R.string.k2go_zim_grp_all), null));
        for (KiwixGroups.Group g : KiwixGroups.ALL) chipRow.addView(chip(getString(g.chipLabel), g.key));
    }

    private View chip(String label, String groupKey) {
        boolean selected = (groupKey == null) ? (selectedGroup == null) : groupKey.equals(selectedGroup);
        int teal = ContextCompat.getColor(requireContext(), R.color.k2go_teal);
        TextView t = new TextView(requireContext());
        t.setText(label);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(px(48));   // ADFA-5033: ≥48dp tap target (spec §9)
        t.setPadding(px(14), px(6), px(14), px(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(px(24));   // full pill at 48dp
        if (selected) {
            bg.setColor(teal);
            t.setTextColor(android.graphics.Color.WHITE);
        } else {
            bg.setColor(android.graphics.Color.TRANSPARENT);
            bg.setStroke(Math.max(1, Math.round(1.4f * getResources().getDisplayMetrics().density)), teal);
            t.setTextColor(teal);
        }
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            selectedGroup = groupKey;
            expanded = false;
            buildChips();
            buildRows();
        });
        return t;
    }

    /** Teal caps section header (MOST CONTENT / group headers). */
    private View sectionHeader(String label) {
        TextView t = new TextView(requireContext());
        t.setText(label);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        t.setLetterSpacing(0.06f);
        t.setPadding(0, px(16), 0, px(8));
        return t;
    }

    /** Material icon per category (first pass; the team can refine the set later). */
    private int iconFor(String key) {
        switch (key) {
            case "wikipedia": case "vikidia":
                return R.drawable.ic_card_wikipedia;
            case "wikibooks": case "libretexts": case "gutenberg": case "wikisource":
                return R.drawable.ic_card_book;
            case "devdocs": case "freecodecamp":
                return R.drawable.ic_card_code;
            case "mooc": case "wikiversity":
                return R.drawable.ic_card_courses;
            case "maps":
                return R.drawable.ic_card_maps;
            case "ted": case "videos":
                return R.drawable.ic_cat_video;
            case "stack_exchange":
                return R.drawable.ic_cat_qa;
            case "phet":
                return R.drawable.ic_cat_science;
            case "wiktionary":
                return R.drawable.ic_cat_translate;
            case "ifixit":
                return R.drawable.ic_cat_build;
            default: // other, zimit, psiram, wikinews, wikiquote, wikivoyage
                return R.drawable.ic_cat_article;
        }
    }

    private boolean matches(KiwixCategories.Category c, String q) {
        if (q.isEmpty()) return true;
        return c.title.toLowerCase(Locale.ROOT).contains(q)
                || c.subtitle.toLowerCase(Locale.ROOT).contains(q)
                || c.key.toLowerCase(Locale.ROOT).contains(q);
    }

    /** "See all N categories ›" — expands the default list into the grouped view. */
    private View seeAllRow(int catCount) {
        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = flatRow();
        TextView label = new TextView(requireContext());
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        label.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        label.setText(getString(R.string.k2go_zim_see_all_cats_fmt, catCount));
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(chevron());
        row.setOnClickListener(v -> { expanded = true; buildRows(); });
        wrap.addView(row);
        wrap.addView(hairline());
        return wrap;
    }

    /** Default state: one muted line summarizing the categories hidden by the current language. */
    private View unavailableLine(int count, String L) {
        TextView t = new TextView(requireContext());
        t.setText(getString(R.string.k2go_zim_unavail_line_fmt, count, langDisplay(L)));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        t.setPadding(0, px(14), 0, px(4));
        t.setOnClickListener(v -> pickLanguage());
        return t;
    }

    /** See-all state: the unavailable categories collapsed into a single muted row (tap = change lang). */
    private View unavailableRow(int count, String L) {
        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = flatRow();
        TextView label = new TextView(requireContext());
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        label.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        label.setText(getString(R.string.k2go_zim_unavail_row_fmt, langDisplay(L)));
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = new TextView(requireContext());
        n.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        n.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        n.setText(String.valueOf(count));
        row.addView(n);
        row.addView(chevron());
        row.setOnClickListener(v -> pickLanguage());
        wrap.addView(row);
        wrap.addView(hairline());
        return wrap;
    }

    /** A flat, horizontal content row (no card): centred vertically, ≥56dp, grid padding. */
    private LinearLayout flatRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(56));
        row.setPadding(0, px(10), 0, px(10));
        return row;
    }

    private View hairline() {
        View v = new View(requireContext());
        v.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.k2go_hairline));
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)));
        return v;
    }

    private TextView chevron() {
        TextView ch = new TextView(requireContext());
        ch.setText("›");
        ch.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        ch.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        ch.setPadding(px(10), 0, 0, 0);
        return ch;
    }

    // ADFA-5033: flat, light row — no per-row card. Icon + name/subtitle + right-aligned count column
    // (teal, redundant with the number) + chevron, over a hairline. Only available categories reach here.
    private View categoryRow(KiwixCategories.Category c, int n) {
        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = flatRow();

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconFor(c.key));
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(24), px(24));
        ilp.rightMargin = px(16);
        row.addView(icon, ilp);

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(text(c.title, R.color.k2go_ink, true));
        text.addView(text(c.subtitle, R.color.k2go_muted, false));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView count = new TextView(requireContext());
        count.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        count.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        count.setText(String.valueOf(n));
        count.setGravity(Gravity.END);
        count.setMinWidth(px(28));
        row.addView(count);
        row.addView(chevron());

        row.setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openZimCategory(c.key);
            }
        });

        wrap.addView(row);
        wrap.addView(hairline());
        return wrap;
    }

    private TextView text(String s, int color, boolean bold) {
        TextView t = new TextView(requireContext());
        t.setTextAppearance(bold
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                : com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setText(s);
        t.setTextColor(ContextCompat.getColor(requireContext(), color));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private void pickLanguage() {
        if (catalog == null) return;
        // Union of languages across all categories, in a searchable picker (hundreds of codes).
        Set<String> set = new java.util.LinkedHashSet<>();
        for (KiwixCategories.Category c : KiwixCategories.ALL) set.addAll(KiwixCatalog.languages(catalog, c.key));
        if (set.isEmpty()) return;
        ZimLanguageDialog.show(requireContext(), getString(R.string.k2go_zim_change),
                new ArrayList<>(set), this::langDisplay, lang(), code -> {
                    if (getActivity() instanceof SetupLibraryActivity) {
                        ((SetupLibraryActivity) getActivity()).setZimLang(code);
                    }
                    updateLangLabel();
                    buildRows();
                }, getString(R.string.k2go_lang_follow_system), () -> {
                    if (getActivity() instanceof SetupLibraryActivity) {
                        ((SetupLibraryActivity) getActivity()).followSystemLang();
                    }
                    updateLangLabel();
                    buildRows();
                });
    }
}
