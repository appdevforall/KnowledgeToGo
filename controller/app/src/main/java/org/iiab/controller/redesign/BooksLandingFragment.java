/*
 * ============================================================================
 * Name        : BooksLandingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850. Get More Books. Browse/search the offline Gutenberg catalog
 *               (/api/books/search), a 2-column cover grid with multi-select; category chips
 *               (Popular / Educational). Books already in the Calibre-Web library are marked and
 *               not selectable. "Add to library" hands the selection to BooksDownloadService
 *               (one at a time, retry, background). Never "Read" here — reading is the home
 *               "Read a Book" card. Covers are colored placeholders (title/author) for now.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BooksLandingFragment extends Fragment {

    private LinearLayout grid, chips;
    private TextView status, downloadsLink;
    private Button addBtn;

    private String filter = "";      // "" = Popular, "educational"
    private String query = "";
    private final List<JSONObject> books = new ArrayList<>();
    private final LinkedHashMap<String, JSONObject> selected = new LinkedHashMap<>();
    private final Set<String> libraryTitles = new HashSet<>();

    private final int[] palette = {R.color.k2go_teal, R.color.k2go_clay, R.color.k2go_leaf, R.color.k2go_amber, R.color.k2go_ink};

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_books_landing, container, false);

        TextView back = root.findViewById(R.id.k2go_books_back);
        back.setText("‹ " + getString(R.string.k2go_gm_hub_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        grid = root.findViewById(R.id.k2go_books_grid);
        chips = root.findViewById(R.id.k2go_books_chips);
        status = root.findViewById(R.id.k2go_books_status);
        addBtn = root.findViewById(R.id.k2go_books_add);
        downloadsLink = root.findViewById(R.id.k2go_books_downloads_link);

        android.widget.EditText search = root.findViewById(R.id.k2go_books_search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim(); loadBooks();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        buildChips();
        addBtn.setOnClickListener(v -> startDownloads());
        downloadsLink.setOnClickListener(v -> openDownloads());

        loadLibrary();
        loadBooks();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLibrary();          // a book may have finished adding; refresh badges
        refreshFooter();
    }

    private void buildChips() {
        chips.removeAllViews();
        chips.addView(chip(getString(R.string.k2go_books_chip_popular), filter.isEmpty(), () -> { filter = ""; query = ""; loadBooks(); }));
        View gap = new View(requireContext());
        chips.addView(gap, new LinearLayout.LayoutParams(px(8), 1));
        chips.addView(chip(getString(R.string.k2go_books_chip_edu), "educational".equals(filter), () -> { filter = "educational"; query = ""; loadBooks(); }));
    }

    private TextView chip(String text, boolean on, Runnable onClick) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setPadding(px(14), px(8), px(14), px(8));
        t.setBackgroundResource(on ? R.drawable.k2go_chip_bg : R.drawable.k2go_pill_bg);
        t.setTextColor(ContextCompat.getColor(requireContext(), on ? R.color.k2go_on_teal : R.color.k2go_ink));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setClickable(true);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private void loadLibrary() {
        BooksClient.library(new BooksClient.ArrayCb() {
            @Override public void onOk(JSONArray rows) {
                if (!isAdded()) return;
                libraryTitles.clear();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject b = rows.optJSONObject(i);
                    if (b != null) libraryTitles.add(b.optString("title", "").toLowerCase(Locale.ROOT).trim());
                }
                render();
            }
            @Override public void onErr(String m) { /* keep whatever we had */ }
        });
    }

    private void loadBooks() {
        status.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.k2go_books_loading));
        grid.removeAllViews();
        BooksClient.search(query, filter, 40, new BooksClient.ArrayCb() {
            @Override public void onOk(JSONArray rows) {
                if (!isAdded()) return;
                books.clear();
                for (int i = 0; i < rows.length(); i++) { JSONObject b = rows.optJSONObject(i); if (b != null) books.add(b); }
                render();
            }
            @Override public void onErr(String m) {
                if (!isAdded()) return;
                status.setVisibility(View.VISIBLE);
                status.setText(getString(R.string.k2go_books_unavailable));
            }
        });
    }

    private boolean inLibrary(JSONObject b) {
        return libraryTitles.contains(b.optString("title", "").toLowerCase(Locale.ROOT).trim());
    }

    private void render() {
        grid.removeAllViews();
        status.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
        if (books.isEmpty()) status.setText(getString(R.string.k2go_books_none));

        for (int i = 0; i < books.size(); i += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int k = i; k < i + 2 && k < books.size(); k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(cell(books.get(k)), lp);
            }
            if (i + 1 >= books.size()) { // pad a lone last cell so it stays half-width
                View pad = new View(requireContext());
                row.addView(pad, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        refreshFooter();
    }

    private View cell(JSONObject b) {
        String title = b.optString("title", "");
        String author = b.optString("author", "");
        String gid = b.optString("gutenberg_id", "");
        boolean lib = inLibrary(b);
        boolean sel = selected.containsKey(gid);

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(px(6), px(6), px(6), px(6));
        box.setLayoutParams(boxLp);

        // Colored cover with the title on it (placeholder; real covers load later from cover_url).
        LinearLayout cover = new LinearLayout(requireContext());
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setPadding(px(10), px(12), px(10), px(12));
        int color = ContextCompat.getColor(requireContext(), palette[Math.abs(title.hashCode()) % palette.length]);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(10));
        if (sel) { bg.setStroke(px(3), ContextCompat.getColor(requireContext(), R.color.k2go_teal)); }
        cover.setBackground(bg);
        cover.setMinimumHeight(px(150));

        TextView tt = new TextView(requireContext());
        tt.setText(title);
        tt.setMaxLines(4);
        tt.setGravity(Gravity.CENTER);
        tt.setTextColor(0xFFFFFFFF);
        tt.setTypeface(tt.getTypeface(), Typeface.BOLD);
        tt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        tt.setTextColor(0xFFFFFFFF);
        cover.addView(tt);

        TextView badge = new TextView(requireContext());
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(0xCCFFFFFF);
        badge.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        badge.setTextColor(0xCCFFFFFF);
        badge.setText(lib ? getString(R.string.k2go_books_in_library)
                : sel ? getString(R.string.k2go_books_selected) : author);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = px(8);
        cover.addView(badge, blp);

        box.addView(cover);

        if (!lib) {
            box.setOnClickListener(v -> {
                if (selected.containsKey(gid)) selected.remove(gid); else selected.put(gid, b);
                render();
            });
        }
        box.setAlpha(lib ? 0.7f : 1f);
        return box;
    }

    private void refreshFooter() {
        int n = selected.size();
        addBtn.setEnabled(n > 0);
        addBtn.setText(n > 0 ? getString(R.string.k2go_books_add_fmt, n) : getString(R.string.k2go_books_add_none));
        boolean active = BooksDownloadService.hasSession();
        downloadsLink.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) downloadsLink.setText(getString(R.string.k2go_books_view_downloads));
    }

    private void startDownloads() {
        if (selected.isEmpty()) return;
        List<String> ids = new ArrayList<>(), titles = new ArrayList<>(), urls = new ArrayList<>();
        for (JSONObject b : selected.values()) {
            ids.add(b.optString("gutenberg_id", ""));
            titles.add(b.optString("title", ""));
            urls.add(b.optString("download_url", ""));
        }
        BooksDownloadService.start(requireContext().getApplicationContext(),
                ids.toArray(new String[0]), titles.toArray(new String[0]), urls.toArray(new String[0]));
        selected.clear();
        openDownloads();
    }

    private void openDownloads() {
        if (getActivity() instanceof SetupLibraryActivity) ((SetupLibraryActivity) getActivity()).openBooksDownloads();
    }
}
