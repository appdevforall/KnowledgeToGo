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
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.os.StatFs;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.applang.data.ContentLanguage;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ZimLandingFragment extends Fragment {

    private static final int COLLAPSED = 15;

    private JSONObject catalog;
    private long freeMb = 0, totalMb = 0;
    private LinearLayout cats;
    private TextView status, langLabel, storageLabel;
    private ProgressBar storageBar;
    private String query = "";
    private boolean expanded = false;

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
        status = root.findViewById(R.id.k2go_zim_status);
        langLabel = root.findViewById(R.id.k2go_zim_lang);
        storageLabel = root.findViewById(R.id.k2go_zim_storage_label);
        storageBar = root.findViewById(R.id.k2go_zim_storage_bar);

        android.widget.EditText search = root.findViewById(R.id.k2go_zim_search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                if (catalog != null) buildRows();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        root.findViewById(R.id.k2go_zim_change).setOnClickListener(v -> pickLanguage());

        try {
            StatFs st = new StatFs(requireContext().getFilesDir().getPath());
            freeMb = st.getAvailableBytes() / (1024L * 1024L);
            totalMb = st.getTotalBytes() / (1024L * 1024L);
        } catch (Exception e) { freeMb = 0; totalMb = 0; }

        updateLangLabel();
        updateStorage();
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
        langLabel.setText(getString(R.string.k2go_zim_lang_fmt, langDisplay(lang())));
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
    }

    private String gb(long mb) {
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        return mb + " MB";
    }

    private void buildRows() {
        cats.removeAllViews();
        String lang = lang();

        List<KiwixCategories.Category> ordered = new ArrayList<>();
        Collections.addAll(ordered, KiwixCategories.ALL);
        Collections.sort(ordered, (a, b) ->
                Integer.compare(KiwixCatalog.totalFiles(catalog, b.key), KiwixCatalog.totalFiles(catalog, a.key)));

        List<KiwixCategories.Category> shown = new ArrayList<>();
        for (KiwixCategories.Category c : ordered) if (matches(c, query)) shown.add(c);

        // Collapse to the first 15 only when not searching; searching shows every match so a
        // low-count category (e.g. TED) is always findable even though it sorts far down.
        boolean limiting = query.isEmpty() && !expanded && shown.size() > COLLAPSED;
        int limit = limiting ? COLLAPSED : shown.size();

        for (int i = 0; i < limit; i++) {
            KiwixCategories.Category c = shown.get(i);
            cats.addView(categoryRow(c, KiwixCatalog.totalFiles(catalog, c.key),
                    KiwixCatalog.count(catalog, c.key, lang)));
        }
        if (limiting) cats.addView(seeAllRow(ordered.size(), totalItems(ordered)));
        if (shown.isEmpty()) status.setText(getString(R.string.k2go_zim_no_match));
        else status.setText("");
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

    private int totalItems(List<KiwixCategories.Category> all) {
        int n = 0;
        for (KiwixCategories.Category c : all) n += KiwixCatalog.totalFiles(catalog, c.key);
        return n;
    }

    private View seeAllRow(int catCount, int itemCount) {
        TextView t = new TextView(requireContext());
        t.setText(getString(R.string.k2go_zim_see_all_fmt, catCount, itemCount));
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), px(12), px(12), px(12));
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setClickable(true);
        t.setOnClickListener(v -> { expanded = true; buildRows(); });
        return t;
    }

    private View categoryRow(KiwixCategories.Category c, int total, int inLang) {
        boolean enabled = total > 0;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = px(8);
        row.setLayoutParams(rlp);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(14), px(12), px(14), px(12));
        row.setAlpha(enabled ? 1f : 0.5f);

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconFor(c.key));
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(26), px(26));
        ilp.rightMargin = px(12);
        row.addView(icon, ilp);

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(text(c.title, R.color.k2go_ink, true));
        text.addView(text(c.subtitle, R.color.k2go_muted, false));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(requireContext());
        right.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        right.setTextColor(ContextCompat.getColor(requireContext(), enabled ? R.color.k2go_ink : R.color.k2go_muted));
        right.setText(enabled ? (total + "   ›") : getString(R.string.k2go_zim_cat_unavailable));
        row.addView(right);

        if (enabled) {
            row.setOnClickListener(v -> {
                if (getActivity() instanceof SetupLibraryActivity) {
                    ((SetupLibraryActivity) getActivity()).openZimCategory(c.key);
                }
            });
        }
        return row;
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
        // Union of languages the Wikipedia project offers (the broadest), sorted by display name.
        Set<String> set = KiwixCatalog.languages(catalog, "wikipedia");
        if (set.isEmpty()) set = KiwixCatalog.languages(catalog, "other");
        final List<String> codes = new ArrayList<>(set);
        Collections.sort(codes, (a, b) -> langDisplay(a).compareToIgnoreCase(langDisplay(b)));
        if (codes.isEmpty()) return;

        final String[] names = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++) names[i] = langDisplay(codes.get(i)) + "  (" + codes.get(i) + ")";

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.k2go_zim_change)
                .setItems(names, (d, which) -> {
                    if (getActivity() instanceof SetupLibraryActivity) {
                        ((SetupLibraryActivity) getActivity()).setZimLang(codes.get(which));
                    }
                    updateLangLabel();
                    buildRows();
                })
                .show();
    }
}
