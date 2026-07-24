/*
 * ============================================================================
 * Name        : ZimCategoryFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. ZIM category detail (screen 2). Lists the titles/variants of one
 *               category in the selected language (merged with the language-agnostic bucket),
 *               multi-select with sizes, an in-category search, sort by size/name, and a
 *               per-item free-space guard. "Add to selection" writes the checked items into the
 *               cross-category cart (SetupLibraryActivity) and returns to the catalog. Reads the
 *               baked KiwixCatalog (instant, from memory).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.os.StatFs;
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

import org.iiab.controller.R;
import org.json.JSONObject;

import java.util.ArrayList;
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
        final String key, label; final long bytes;
        boolean checked;
        Entry(String key, String label, long bytes) { this.key = key; this.label = label; this.bytes = bytes; }
    }

    private String project, lang;
    private final List<Entry> entries = new ArrayList<>();
    private String sortBy = "size";
    private String query = "";
    private long freeMb = 0, totalMb = 0;

    private LinearLayout list;
    private TextView freeLabel, sortSize, sortName, sub;
    private ProgressBar bar;
    private Button add;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

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
        sub = root.findViewById(R.id.k2go_zc_sub);
        freeLabel = root.findViewById(R.id.k2go_zc_free);
        bar = root.findViewById(R.id.k2go_zc_bar);
        add = root.findViewById(R.id.k2go_zc_add);
        sortSize = root.findViewById(R.id.k2go_zc_sort_size);
        sortName = root.findViewById(R.id.k2go_zc_sort_name);

        android.widget.EditText search = root.findViewById(R.id.k2go_zc_search);
        search.setHint(getString(R.string.k2go_zc_search_hint, cat != null ? cat.title : project));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT); render();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        sortSize.setOnClickListener(v -> { sortBy = "size"; render(); });
        sortName.setOnClickListener(v -> { sortBy = "name"; render(); });

        try {
            StatFs st = new StatFs(requireContext().getFilesDir().getPath());
            freeMb = st.getAvailableBytes() / (1024L * 1024L);
            totalMb = st.getTotalBytes() / (1024L * 1024L);
        } catch (Exception e) { freeMb = 0; totalMb = 0; }

        loadEntries(root);
        return root;
    }

    private void loadEntries(View root) {
        KiwixCatalog.getOrFetch(requireContext(), new KiwixCatalog.Listener() {
            @Override public void onReady(JSONObject catalog) {
                if (!isAdded()) return;
                entries.clear();
                JSONObject data = KiwixCatalog.langDataMerged(catalog, project, lang);
                LinkedHashMap<String, Long> cart = cart();
                String prefix = project + "|" + lang + "|";
                for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = data.optJSONObject(k);
                    if (v == null) continue;
                    Entry e = new Entry(k, label(v.optString("creator"), v.optString("flavour")),
                            v.optLong("size", 0));
                    e.checked = cart.containsKey(prefix + k);
                    entries.add(e);
                }
                render();
            }
            @Override public void onError(String message) {
                if (isAdded()) freeLabel.setText(getString(R.string.k2go_zim_unavailable));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Long> cart() {
        return (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getZimCart() : new LinkedHashMap<>();
    }

    private String label(String creator, String flavour) {
        if (creator == null) creator = "";
        if (flavour == null || flavour.isEmpty()) flavour = "all";
        boolean creatorIsProject = creator.equalsIgnoreCase(project)
                || creator.toLowerCase(Locale.ROOT).startsWith(project.toLowerCase(Locale.ROOT));
        String pf = "all".equals(flavour) ? "All" : flavour.replace('_', ' ').replace('-', ' ');
        if (creatorIsProject) return pf;
        return "all".equals(flavour) ? creator : creator + " · " + pf;
    }

    private String langDisplay(String code) {
        try {
            Locale l = new Locale(code);
            String n = l.getDisplayName(l);
            if (n == null || n.isEmpty()) n = code;
            return n.substring(0, 1).toUpperCase(l) + n.substring(1);
        } catch (Exception e) { return code; }
    }

    private void render() {
        sub.setText(getString(R.string.k2go_zc_sub_fmt, langDisplay(lang), entries.size()));
        // sort
        Collections.sort(entries, (a, b) -> "name".equals(sortBy)
                ? a.label.compareToIgnoreCase(b.label)
                : Long.compare(b.bytes, a.bytes));
        chip(sortSize, "size".equals(sortBy));
        chip(sortName, "name".equals(sortBy));

        list.removeAllViews();
        for (Entry e : entries) {
            if (!query.isEmpty() && !e.label.toLowerCase(Locale.ROOT).contains(query)) continue;
            list.addView(row(e));
        }
        updateTotals();
    }

    private void chip(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.k2go_chip_bg : R.drawable.k2go_pill_bg);
        t.setTextColor(ContextCompat.getColor(requireContext(), on ? R.color.k2go_on_teal : R.color.k2go_ink));
    }

    private View row(Entry e) {
        boolean fits = freeMb <= 0 || (e.bytes / (1024L * 1024L)) <= freeMb;

        LinearLayout r = new LinearLayout(requireContext());
        r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = px(8);
        r.setLayoutParams(rlp);
        r.setBackgroundResource(R.drawable.k2go_card_bg);
        r.setPadding(px(12), px(10), px(12), px(10));

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        CheckBox cb = new CheckBox(requireContext());
        cb.setChecked(e.checked && fits);
        cb.setEnabled(fits);
        cb.setClickable(false);
        cb.setFocusable(false);
        top.addView(cb);

        TextView name = new TextView(requireContext());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        name.setText(e.label);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = px(6);
        top.addView(name, nlp);

        TextView size = new TextView(requireContext());
        size.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        size.setText(gb(e.bytes / (1024L * 1024L)));
        size.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        top.addView(size);
        r.addView(top);

        if (!fits) {
            TextView warn = new TextView(requireContext());
            warn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            warn.setText(getString(R.string.k2go_zc_nospace, gb(e.bytes / (1024L * 1024L)), gb(freeMb)));
            warn.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_amber_text));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = px(2); wlp.leftMargin = px(34);
            r.addView(warn, wlp);
        } else {
            r.setOnClickListener(v -> { e.checked = !e.checked; cb.setChecked(e.checked); updateTotals(); });
        }
        return r;
    }

    private long checkedMb() {
        long b = 0;
        for (Entry e : entries) if (e.checked) b += e.bytes;
        return b / (1024L * 1024L);
    }

    private void updateTotals() {
        long sel = checkedMb();
        // Preview: other categories already in the cart + this screen's current checks.
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
        java.util.Iterator<String> it = cart.keySet().iterator();
        while (it.hasNext()) if (it.next().startsWith(prefix)) it.remove();
        for (Entry e : entries) if (e.checked) cart.put(prefix + e.key, e.bytes);
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    private String gb(long mb) {
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        return mb + " MB";
    }
}
