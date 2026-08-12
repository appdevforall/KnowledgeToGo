/*
 * ============================================================================
 * Name        : ZimCategoryFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. ZIM category detail (screen 2). Fixed header + fixed bottom bar;
 *               only the list scrolls. Items of one category in the selected language (merged
 *               with the 'mul' bucket), multi-select with sizes and a per-item free-space guard.
 *               Language is changeable via the label beside the title. Three views: By size and
 *               A–Z (each a bidirectional toggle) and Grouped (Wikipedia only, coverage groups
 *               with natural labels reused from the wizard's Wikipedia picker). "Add to
 *               selection" writes the checks to the cross-category cart. Reads the baked catalog.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class ZimCategoryFragment extends Fragment {

    private static final String ARG_PROJECT = "project";

    public static ZimCategoryFragment newInstance(String project) {
        ZimCategoryFragment f = new ZimCategoryFragment();
        Bundle b = new Bundle();
        b.putString(ARG_PROJECT, project);
        f.setArguments(b);
        return f;
    }

    private static final class Entry {
        final String key, creator, flavour; final long bytes; boolean checked;
        Entry(String key, String creator, String flavour, long bytes) {
            this.key = key; this.creator = creator; this.flavour = flavour; this.bytes = bytes;
        }
    }

    private String project, lang;
    private JSONObject catalog;
    private final List<Entry> entries = new ArrayList<>();
    private String view = "size";      // "size" | "name" | "group"
    private int sizeDir = -1;          // -1 largest first, +1 smallest first
    private int nameDir = 1;           // +1 A→Z, -1 Z→A
    private String query = "";
    private long freeMb = 0, totalMb = 0;

    private LinearLayout list;
    private TextView freeLabel, sortSize, sortName, sortGroup, count, langCurrent, langSub;
    private ProgressBar bar;
    private Button add;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
    private boolean isWiki() { return "wikipedia".equals(project); }
    private boolean isCanonical(String f) { return Arrays.asList(InstallationPlanner.CANONICAL_VARIANTS).contains(f); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_zim_category, container, false);
        project = getArguments() != null ? getArguments().getString(ARG_PROJECT) : "";
        lang = (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getZimLang() : "en";

        KiwixCategories.Category cat = KiwixCategories.byKey(project);
        TextView back = root.findViewById(R.id.k2go_zc_back);
        back.setText("‹ " + getString(R.string.k2go_zim_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        ((TextView) root.findViewById(R.id.k2go_zc_title)).setText(cat != null ? cat.title : project);

        list = root.findViewById(R.id.k2go_zc_list);
        freeLabel = root.findViewById(R.id.k2go_zc_free);
        bar = root.findViewById(R.id.k2go_zc_bar);
        add = root.findViewById(R.id.k2go_zc_add);
        sortSize = root.findViewById(R.id.k2go_zc_sort_size);
        sortName = root.findViewById(R.id.k2go_zc_sort_name);
        sortGroup = root.findViewById(R.id.k2go_zc_sort_group);
        count = root.findViewById(R.id.k2go_zc_count);   // ADFA-5033: item count, list metadata
        langCurrent = root.findViewById(R.id.k2go_zc_lang_current);
        langSub = root.findViewById(R.id.k2go_zc_lang_sub);
        root.findViewById(R.id.k2go_zc_lang_box).setOnClickListener(v -> pickLanguage());   // ADFA-5033: whole-row control

        android.widget.EditText search = root.findViewById(R.id.k2go_zc_search);
        search.setHint(getString(R.string.k2go_zc_search_hint, cat != null ? cat.title : project));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT); render();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        sortSize.setOnClickListener(v -> { if ("size".equals(view)) sizeDir = -sizeDir; else view = "size"; render(); });
        sortName.setOnClickListener(v -> { if ("name".equals(view)) nameDir = -nameDir; else view = "name"; render(); });
        sortGroup.setOnClickListener(v -> { view = "group"; render(); });
        sortGroup.setVisibility(isWiki() ? View.VISIBLE : View.GONE);

        // ADFA-5105: shared free-space probe instead of a per-screen StatFs copy.
        Long fb = org.iiab.controller.storage.StorageProbe.freeBytes(requireContext());
        Long tb = org.iiab.controller.storage.StorageProbe.totalBytes(requireContext());
        freeMb = (fb == null ? 0L : fb) / (1024L * 1024L);
        totalMb = (tb == null ? 0L : tb) / (1024L * 1024L);

        KiwixCatalog.getOrFetch(requireContext(), new KiwixCatalog.Listener() {
            @Override public void onReady(JSONObject c) { if (!isAdded()) return; catalog = c; rebuild(); }
            @Override public void onError(String m) { if (isAdded()) freeLabel.setText(getString(R.string.k2go_zim_unavailable)); }
        });
        return root;
    }

    /** (Re)build the entry list for the current project + language, then render. */
    private void rebuild() {
        entries.clear();
        JSONObject data = KiwixCatalog.langData(catalog, project, lang);  // strict: this language only
        LinkedHashMap<String, Long> cart = cart();
        String prefix = project + "|" + lang + "|";
        if (data != null) {
            for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject v = data.optJSONObject(k);
                if (v == null) continue;
                Entry e = new Entry(k, v.optString("creator"), v.optString("flavour"), v.optLong("size", 0));
                e.checked = cart.containsKey(prefix + k);
                entries.add(e);
            }
        }
        render();
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Long> cart() {
        return (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getZimCart() : new LinkedHashMap<>();
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

    private String label(String creator, String flavour) {
        if (creator == null) creator = "";
        if (flavour == null || flavour.isEmpty()) flavour = "all";
        if (isWiki() && isCanonical(flavour)) return WikiVariants.label(requireContext(), flavour);
        boolean creatorIsProject = creator.equalsIgnoreCase(project)
                || creator.toLowerCase(Locale.ROOT).startsWith(project.toLowerCase(Locale.ROOT));
        String pf = "all".equals(flavour) ? "All" : flavour.replace('_', ' ').replace('-', ' ');
        if (creatorIsProject) return pf;
        return "all".equals(flavour) ? creator : creator + " · " + pf;
    }

    private void render() {
        int shownCount = 0;
        for (Entry e : entries) if (passes(e)) shownCount++;
        // ADFA-5033: "N items" normally, live "N results" while searching — no language (that's the selector).
        count.setText(getString(query.isEmpty() ? R.string.k2go_zc_count_items : R.string.k2go_zc_count_results, shownCount));
        langCurrent.setText(getString(R.string.k2go_zim_lang_fmt, langDisplay(lang)));
        boolean manual = (getActivity() instanceof SetupLibraryActivity)
                && ((SetupLibraryActivity) getActivity()).isZimLangManual();
        langSub.setText(manual ? R.string.k2go_zim_lang_state_manual : R.string.k2go_zim_lang_state_system);
        sortSize.setText(getString(R.string.k2go_zc_sort_size) + (sizeDir < 0 ? " ▼" : " ▲"));
        sortName.setText((nameDir > 0 ? getString(R.string.k2go_zc_sort_name) : getString(R.string.k2go_zc_sort_name_desc))
                + (nameDir > 0 ? " ▼" : " ▲"));
        chip(sortSize, "size".equals(view));
        chip(sortName, "name".equals(view));
        chip(sortGroup, "group".equals(view));

        list.removeAllViews();
        if ("group".equals(view) && isWiki()) renderGrouped();
        else renderList();
        if (list.getChildCount() == 0) {
            TextView none = new TextView(requireContext());
            none.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            none.setText(getString(R.string.k2go_zc_none));
            none.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            none.setPadding(0, px(16), 0, px(16));
            list.addView(none);
        }
        updateTotals();
    }

    private void renderList() {
        List<Entry> shown = new ArrayList<>();
        for (Entry e : entries) if (passes(e)) shown.add(e);
        if ("name".equals(view))
            Collections.sort(shown, (a, b) -> nameDir * label(a.creator, a.flavour).compareToIgnoreCase(label(b.creator, b.flavour)));
        else
            Collections.sort(shown, (a, b) -> sizeDir * Long.compare(a.bytes, b.bytes));
        for (Entry e : shown) list.addView(row(e, label(e.creator, e.flavour), 0));
    }

    private void renderGrouped() {
        LinkedHashMap<String, Entry> byFlavour = new LinkedHashMap<>();
        List<Entry> topics = new ArrayList<>();
        for (Entry e : entries) {
            if (isCanonical(e.flavour)) byFlavour.put(e.flavour, e);
            else topics.add(e);
        }
        String[] covs = {"all", "top1m", "top"};
        String[] dets = {"maxi", "nopic", "mini"};
        for (String cov : covs) {
            List<Entry> present = new ArrayList<>();
            for (String det : dets) {
                Entry e = byFlavour.get(cov + "_" + det);
                if (e != null && passes(e)) present.add(e);
            }
            if (present.isEmpty()) continue;
            addHeader(WikiVariants.coverageName(requireContext(), cov), WikiVariants.coverageDesc(requireContext(), cov));
            for (Entry e : present)
                list.addView(row(e, WikiVariants.detailName(requireContext(), WikiVariants.detailOf(e.flavour)), px(12)));
        }
        List<Entry> t = new ArrayList<>();
        for (Entry e : topics) if (passes(e)) t.add(e);
        if (!t.isEmpty()) {
            Collections.sort(t, (a, b) -> Long.compare(b.bytes, a.bytes));
            addHeader(getString(R.string.k2go_zc_group_topics), null);
            for (Entry e : t) list.addView(row(e, label(e.creator, e.flavour), px(12)));
        }
    }

    private boolean passes(Entry e) {
        return query.isEmpty() || label(e.creator, e.flavour).toLowerCase(Locale.ROOT).contains(query);
    }

    private void addHeader(String title, String desc) {
        LinearLayout h = new LinearLayout(requireContext());
        h.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = px(12); hlp.bottomMargin = px(4);
        h.setLayoutParams(hlp);
        TextView t = new TextView(requireContext());
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setText(title);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        h.addView(t);
        if (desc != null) {
            TextView d = new TextView(requireContext());
            d.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            d.setText(desc);
            d.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            h.addView(d);
        }
        list.addView(h);
    }

    private void chip(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.k2go_chip_bg : R.drawable.k2go_pill_bg);
        // ADFA-4910: white on the selected (teal) chip (same fix as Books / MapsChoose).
        t.setTextColor(ContextCompat.getColor(requireContext(), on ? android.R.color.white : R.color.k2go_ink));
    }

    // ADFA-5033: flat list row (spec §10) — a simple line with a hairline between rows, NOT a card.
    // The only rounded/filled shape is the SELECTED row's highlight; unselected rows are plain.
    private View row(Entry e, String labelText, int indent) {
        boolean fits = freeMb <= 0 || (e.bytes / (1024L * 1024L)) <= freeMb;
        boolean selected = e.checked && fits;

        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = indent;
        wrap.setLayoutParams(wlp);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(12), px(10), px(12), px(10));
        if (selected) content.setBackground(selectedHighlight());   // the one rounded/filled shape

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(px(36));

        CheckBox cb = new CheckBox(requireContext());
        cb.setChecked(selected);
        cb.setEnabled(fits);
        cb.setClickable(false);
        cb.setFocusable(false);
        top.addView(cb);

        TextView name = new TextView(requireContext());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        name.setText(labelText);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        if (selected) name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = px(8);
        top.addView(name, nlp);

        TextView size = new TextView(requireContext());
        size.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        size.setText(gb(e.bytes / (1024L * 1024L)));
        size.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        size.setGravity(Gravity.END);
        top.addView(size);
        content.addView(top);

        if (!fits) {
            TextView warn = new TextView(requireContext());
            warn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            warn.setText(getString(R.string.k2go_zc_nospace, gb(e.bytes / (1024L * 1024L)), gb(freeMb)));
            warn.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_amber_text));
            LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            warnLp.topMargin = px(2); warnLp.leftMargin = px(36);
            content.addView(warn, warnLp);
        } else {
            content.setOnClickListener(v -> {
                e.checked = !e.checked;
                cb.setChecked(e.checked);   // fits is true in this branch
                content.setBackground(e.checked ? selectedHighlight() : null);
                name.setTypeface(null, e.checked ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                updateTotals();
            });
        }

        wrap.addView(content);
        wrap.addView(hairline());
        return wrap;
    }

    /** Rounded, subtly-filled highlight for the selected row (the only non-flat shape in the list). */
    private android.graphics.drawable.GradientDrawable selectedHighlight() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                ContextCompat.getColor(requireContext(), R.color.k2go_teal), 0x33));
        return g;
    }

    private View hairline() {
        View v = new View(requireContext());
        v.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.k2go_hairline));
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)));
        return v;
    }

    private long checkedMb() {
        long b = 0;
        for (Entry e : entries) if (e.checked) b += e.bytes;
        return b / (1024L * 1024L);
    }

    private void updateTotals() {
        long sel = checkedMb();
        long others = 0;
        String prefix = project + "|" + lang + "|";
        for (java.util.Map.Entry<String, Long> en : cart().entrySet())
            if (!en.getKey().startsWith(prefix)) others += en.getValue();
        long othersMb = others / (1024L * 1024L);
        long used = Math.max(0, totalMb - freeMb);
        int pct = totalMb > 0 ? (int) Math.min(100, Math.round((used + othersMb + sel) * 100.0 / totalMb)) : 0;
        bar.setProgress(pct);
        freeLabel.setText(getString(R.string.k2go_zim_storage_fmt, gb(used), gb(othersMb + sel), gb(freeMb)));
        add.setText(getString(R.string.k2go_zc_add_fmt, gb(sel)));
        add.setOnClickListener(v -> commit());
    }

    private void commit() {
        LinkedHashMap<String, Long> cart = cart();
        String prefix = project + "|" + lang + "|";
        Iterator<String> it = cart.keySet().iterator();
        while (it.hasNext()) if (it.next().startsWith(prefix)) it.remove();
        for (Entry e : entries) if (e.checked) cart.put(prefix + e.key, e.bytes);
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    private void pickLanguage() {
        if (catalog == null) return;
        List<String> codes = new ArrayList<>(KiwixCatalog.languages(catalog, project));
        if (codes.isEmpty()) return;
        ZimLanguageDialog.show(requireContext(), getString(R.string.k2go_zim_change),
                codes, this::langDisplay, lang, code -> {
                    lang = code;
                    if (getActivity() instanceof SetupLibraryActivity)
                        ((SetupLibraryActivity) getActivity()).setZimLang(lang);
                    rebuild();
                }, getString(R.string.k2go_lang_follow_system), () -> {
                    if (getActivity() instanceof SetupLibraryActivity)
                        ((SetupLibraryActivity) getActivity()).followSystemLang();
                    lang = (getActivity() instanceof SetupLibraryActivity)
                            ? ((SetupLibraryActivity) getActivity()).getZimLang() : lang;
                    rebuild();
                });
    }

    private String gb(long mb) {   // ADFA-4910: one standard size formatter for the whole UI
        return org.iiab.controller.util.ByteFormatter.humanMb(mb);
    }
}
