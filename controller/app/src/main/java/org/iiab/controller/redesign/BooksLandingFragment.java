/*
 * ============================================================================
 * Name        : BooksLandingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850. Get More Books. Browse/search the offline Gutenberg catalog
 *               (/api/books/search), a 2-column cover grid with multi-select; category chips
 *               (Popular / Educational / My books). "Add to library" hands the selection to
 *               BooksDownloadService (one at a time, retry, background). The "My books" chip lists
 *               the local Calibre-Web library (/api/books/library); tapping a local book opens its
 *               Calibre-Web page (all details + Read / Download) — reading itself lives there and on
 *               the home "Read a Book" card, never here.
 *
 *               State is shown with a band UNDER the title (not a whole-card tint, which was hard to
 *               read): a green "In your books" band for library titles, a gray "Selected" band for
 *               picks. Covers use a fixed palette (k2go_cover_*) with white text so contrast holds in
 *               both light and dark themes — semantic tokens flipped to near-white in dark mode.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Intent;
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

import org.iiab.controller.PortalActivity;
import org.iiab.controller.R;
import org.iiab.controller.config.BoxEndpoints;
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
    private TextView status, downloadsLink, langPill;
    private Button addBtn;

    private String filter = "";      // "" = Popular, "educational", "local" = My books
    private String query = "";
    private String lang = "";        // "" = all languages, else an ISO code from the catalog
    private final List<String> langCodes = new ArrayList<>();   // languages present in the catalog
    private final List<JSONObject> books = new ArrayList<>();
    private final LinkedHashMap<String, JSONObject> selected = new LinkedHashMap<>();
    private final Set<String> libraryTitles = new HashSet<>();

    // Fixed cover palette: dark enough for white text, identical in light + dark themes.
    private final int[] palette = {
            R.color.k2go_cover_1, R.color.k2go_cover_2, R.color.k2go_cover_3,
            R.color.k2go_cover_4, R.color.k2go_cover_5, R.color.k2go_cover_6};

    private boolean isLocal() { return "local".equals(filter); }

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
        langPill = root.findViewById(R.id.k2go_books_lang);
        langPill.setOnClickListener(v -> openLanguagePicker());
        updateLangPill();

        android.widget.EditText search = root.findViewById(R.id.k2go_books_search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (isLocal()) return;           // search applies to the catalog, not the local list
                query = s.toString().trim(); loadBooks();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        buildChips();
        addBtn.setOnClickListener(v -> startDownloads());
        downloadsLink.setOnClickListener(v -> openDownloads());

        loadLanguages();
        loadLibrary();
        loadBooks();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLibrary();          // a book may have finished adding; refresh badges
        if (isLocal()) loadBooks();
        refreshFooter();
    }

    private void buildChips() {
        chips.removeAllViews();
        chips.addView(chip(getString(R.string.k2go_books_chip_popular), filter.isEmpty(), () -> { filter = ""; query = ""; onFilterChanged(); }));
        chips.addView(gap());
        chips.addView(chip(getString(R.string.k2go_books_chip_edu), "educational".equals(filter), () -> { filter = "educational"; query = ""; onFilterChanged(); }));
        chips.addView(gap());
        chips.addView(chip(getString(R.string.k2go_books_chip_local), isLocal(), () -> { filter = "local"; query = ""; onFilterChanged(); }));
    }

    private View gap() {
        View g = new View(requireContext());
        g.setLayoutParams(new LinearLayout.LayoutParams(px(8), 1));
        return g;
    }

    private void onFilterChanged() {
        buildChips();       // repaint active state
        updateLangPill();   // hidden for the local library (not catalog-language filtered)
        loadBooks();
        refreshFooter();
    }

    private void loadLanguages() {
        BooksClient.languages(new BooksClient.ArrayCb() {
            @Override public void onOk(JSONArray rows) {
                if (!isAdded()) return;
                langCodes.clear();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    String code = r != null ? r.optString("code", "").trim() : "";
                    if (!code.isEmpty()) langCodes.add(code);
                }
            }
            @Override public void onErr(String m) { /* leave the picker empty; "All" still works */ }
        });
    }

    private String langName(String code) {
        if (code == null || code.isEmpty()) return code;
        String n = new Locale(code).getDisplayLanguage();
        return (n == null || n.isEmpty() || n.equalsIgnoreCase(code)) ? code : n;
    }

    private void updateLangPill() {
        if (langPill == null) return;
        langPill.setVisibility(isLocal() ? View.GONE : View.VISIBLE);
        langPill.setText(lang.isEmpty()
                ? getString(R.string.k2go_books_lang_all)
                : getString(R.string.k2go_books_lang_fmt, langName(lang)));
    }

    private void openLanguagePicker() {
        ZimLanguageDialog.show(requireContext(), getString(R.string.k2go_books_lang_title),
                langCodes, this::langName, lang,
                code -> { lang = code; updateLangPill(); loadBooks(); },
                getString(R.string.k2go_books_lang_all),
                () -> { lang = ""; updateLangPill(); loadBooks(); });
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
                if (!isLocal()) render();
            }
            @Override public void onErr(String m) { /* keep whatever we had */ }
        });
    }

    private void loadBooks() {
        status.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.k2go_books_loading));
        grid.removeAllViews();
        BooksClient.ArrayCb cb = new BooksClient.ArrayCb() {
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
        };
        if (isLocal()) BooksClient.library(cb);
        else BooksClient.search(query, filter, lang, 40, cb);
    }

    private boolean inLibrary(JSONObject b) {
        return libraryTitles.contains(b.optString("title", "").toLowerCase(Locale.ROOT).trim());
    }

    private void render() {
        grid.removeAllViews();
        boolean empty = books.isEmpty();
        status.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) status.setText(getString(isLocal() ? R.string.k2go_books_local_none : R.string.k2go_books_none));

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
        boolean local = isLocal();
        String title = b.optString("title", "");
        String author = b.optString("author", "");
        String gid = b.optString("gutenberg_id", "");
        boolean lib = !local && inLibrary(b);
        boolean sel = !local && selected.containsKey(gid);

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(px(6), px(6), px(6), px(6));
        box.setLayoutParams(boxLp);

        // Colored cover (placeholder; real covers load later from cover_url).
        LinearLayout cover = new LinearLayout(requireContext());
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setPadding(px(12), px(14), px(12), px(14));
        int color = ContextCompat.getColor(requireContext(), palette[Math.abs(title.hashCode()) % palette.length]);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(10));
        if (sel) bg.setStroke(px(3), ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        cover.setBackground(bg);
        cover.setMinimumHeight(px(150));

        TextView tt = new TextView(requireContext());
        tt.setText(title);
        tt.setMaxLines(4);
        tt.setGravity(Gravity.CENTER);
        tt.setTypeface(tt.getTypeface(), Typeface.BOLD);
        tt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        tt.setTextColor(0xFFFFFFFF);
        cover.addView(tt);

        // State band UNDER the title (green = in your books, gray = selected); else the author.
        if (lib) {
            cover.addView(band(R.color.k2go_band_library, getString(R.string.k2go_books_in_library)));
        } else if (sel) {
            cover.addView(band(R.color.k2go_band_selected, getString(R.string.k2go_books_selected)));
        } else {
            TextView sub = new TextView(requireContext());
            sub.setGravity(Gravity.CENTER);
            sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            sub.setTextColor(0xCCFFFFFF);
            sub.setText(author);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = px(8);
            cover.addView(sub, slp);
        }

        box.addView(cover);

        if (local) {
            final int id = b.optInt("id", -1);
            box.setOnClickListener(v -> openLocalBook(id));
        } else if (!lib) {
            box.setOnClickListener(v -> {
                if (selected.containsKey(gid)) selected.remove(gid); else selected.put(gid, b);
                render();
            });
        }
        return box;
    }

    /** A pill band under the title. A faint white stroke keeps it legible on any cover color. */
    private TextView band(int colorRes, String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), px(4), px(12), px(4));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(0xFFFFFFFF);
        GradientDrawable d = new GradientDrawable();
        d.setColor(ContextCompat.getColor(requireContext(), colorRes));
        d.setCornerRadius(px(8));
        d.setStroke(px(1), 0x4DFFFFFF);
        t.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void refreshFooter() {
        if (isLocal()) {
            addBtn.setVisibility(View.GONE);
        } else {
            addBtn.setVisibility(View.VISIBLE);
            int n = selected.size();
            addBtn.setEnabled(n > 0);
            addBtn.setText(n > 0 ? getString(R.string.k2go_books_add_fmt, n) : getString(R.string.k2go_books_add_none));
        }
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

    /** Open a local book's Calibre-Web page (details + Read / Download) in the in-app portal. */
    private void openLocalBook(int id) {
        if (id < 0) return;
        Intent i = new Intent(requireContext(), PortalActivity.class);
        i.putExtra("TARGET_URL", BoxEndpoints.BASE + "/books/book/" + id);
        startActivity(i);
    }
}
