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
package org.appdevforall.k2go.redesign;

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

import org.appdevforall.k2go.PortalActivity;
import com.google.android.material.chip.Chip;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.config.BoxEndpoints;
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

    // Wizard mode (ADFA-4853): pre-install. Source is the OFFLINE catalog asset, there is no live
    // server, and "Add" writes to the persisted wishlist (drained after install) instead of the
    // live download service. "In your books" here means "already in your setup order".
    private boolean wizard = false;

    // ADFA-5329: incremental "Load more" — BATCH titles per tap; hasMore/loading drive the footer.
    private static final int BATCH = 40;
    private boolean hasMore = false;
    private boolean loading = false;

    /** Open the Books screen in wizard (pre-install, offline) mode. */
    public static BooksLandingFragment newInstance(boolean wizard) {
        BooksLandingFragment f = new BooksLandingFragment();
        Bundle b = new Bundle();
        b.putBoolean("wizard", wizard);
        f.setArguments(b);
        return f;
    }

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
        wizard = getArguments() != null && getArguments().getBoolean("wizard", false);

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
        addBtn.setOnClickListener(v -> reviewSelection());
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
        if (wizard) return;   // offline asset has no bookshelves (Educational) and no live library (My books)
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
        BooksClient.ArrayCb cb = new BooksClient.ArrayCb() {
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
        };
        if (wizard) BooksCatalogAsset.languages(requireContext(), cb);
        else BooksClient.languages(cb);
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

    // K2GO-385 (PR3): the shared filter chip (8dp corner, 32dp, check when selected). Replaces the
    // per-screen k2go_chip_bg/k2go_pill_bg drawable pair that had drifted from the other filter surfaces.
    private Chip chip(String text, boolean on, Runnable onClick) {
        return K2GoFilterChip.create(requireContext(), text, on, v -> onClick.run());
    }

    private void loadLibrary() {
        if (wizard) { render(); return; }   // "in your setup" comes from the wishlist; no live library
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

    /** (Re)load the first batch — called on open and whenever the filter, search or language changes. */
    private void loadBooks() {
        hasMore = true;
        fetchBatch(0, false);
    }

    /** ADFA-5329: append the next batch on demand ("Load more"). */
    private void loadMore() {
        if (loading || !hasMore) return;
        fetchBatch(books.size(), true);
    }

    private void fetchBatch(int offset, boolean append) {
        android.util.Log.d("K2Go-Books", "fetchBatch off=" + offset + " append=" + append
                + " wizard=" + wizard + " filter=" + filter + " lang=" + lang + " q=" + query);
        loading = true;
        if (!append) {
            books.clear();
            status.setVisibility(View.VISIBLE);
            status.setText(getString(R.string.k2go_books_loading));
            grid.removeAllViews();
        } else {
            render();   // show the "Loading…" footer while the next batch arrives
        }
        BooksClient.ArrayCb cb = new BooksClient.ArrayCb() {
            @Override public void onOk(JSONArray rows) {
                if (!isAdded()) return;
                loading = false;
                if (!append) books.clear();
                for (int i = 0; i < rows.length(); i++) { JSONObject b = rows.optJSONObject(i); if (b != null) books.add(b); }
                // The local library returns everything at once; a short batch means we hit the end.
                hasMore = !isLocal() && rows.length() >= BATCH;
                render();
            }
            @Override public void onErr(String m) {
                if (!isAdded()) return;
                loading = false;
                if (append) { render(); return; }   // keep what we have; the Load more stays for a retry
                status.setVisibility(View.VISIBLE);
                status.setText(getString(wizard ? R.string.k2go_books_offline_error : R.string.k2go_books_unavailable));
            }
        };
        if (wizard) BooksCatalogAsset.search(requireContext(), query, lang, offset, BATCH, cb);
        else if (isLocal()) BooksClient.library(cb);
        else BooksClient.search(query, filter, lang, offset, BATCH, cb);
    }

    private boolean inLibrary(JSONObject b) {
        if (wizard) return BooksWishlist.contains(requireContext(), b.optString("gutenberg_id", ""));
        return libraryTitles.contains(b.optString("title", "").toLowerCase(Locale.ROOT).trim());
    }

    private void render() {
        grid.removeAllViews();
        boolean empty = books.isEmpty();
        status.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) status.setText(getString(isLocal() ? R.string.k2go_books_local_none : R.string.k2go_books_none));

        final int[] cidx = coverIndices();
        for (int i = 0; i < books.size(); i += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int k = i; k < i + 2 && k < books.size(); k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(px(5), px(5), px(5), px(5));   // slight gutter so cards don't touch
                row.addView(cell(books.get(k), palette[cidx[k]]), lp);
            }
            if (i + 1 >= books.size()) { // pad a lone last cell so it stays half-width
                View pad = new View(requireContext());
                row.addView(pad, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        appendPaginationFooter();   // ADFA-5329: Load more / loading / end-of-list
        refreshFooter();
    }

    /** ADFA-5329: the "Load more" / loading / end-of-list footer, appended under the grid (inside the
     *  scroll, above the fixed Add bar). Not shown for the local library, which returns all rows. */
    private void appendPaginationFooter() {
        if (isLocal() || books.isEmpty()) return;
        if (loading) {
            grid.addView(footerText(getString(R.string.k2go_books_loading), false));
            return;
        }
        if (hasMore) {
            grid.addView(footerText(getString(R.string.k2go_books_showing_fmt, books.size()), false));
            TextView more = footerText(getString(R.string.k2go_books_load_more), true);
            more.setOnClickListener(v -> loadMore());
            grid.addView(more);
        } else {
            grid.addView(footerText(getString(R.string.k2go_books_all_fmt, books.size()), false));
        }
    }

    private TextView footerText(String text, boolean asButton) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(8), px(10), px(8), px(6));
        t.setLayoutParams(lp);
        if (asButton) {
            t.setPadding(px(10), px(12), px(10), px(12));
            t.setBackgroundResource(R.drawable.k2go_getmore_bg);
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));   // after appearance
            t.setClickable(true);
            t.setFocusable(true);
        } else {
            t.setPadding(px(10), px(6), px(10), px(6));
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));   // after appearance
        }
        return t;
    }

    /** Cover colors chosen so a card never repeats its left neighbor's or the one above it (2-col
     *  grid). Seeded from the title hash for variety, then nudged off any collision — no meaning,
     *  just fewer same-color blocks. */
    private int[] coverIndices() {
        int n = books.size();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            int left = (i % 2 == 1) ? idx[i - 1] : -1;   // previous card in the same row
            int above = (i >= 2) ? idx[i - 2] : -1;      // card directly above (two columns)
            int start = Math.abs(books.get(i).optString("title", "").hashCode()) % palette.length;
            int chosen = start;
            for (int t = 0; t < palette.length; t++) {
                int cand = (start + t) % palette.length;
                if (cand != left && cand != above) { chosen = cand; break; }
            }
            idx[i] = chosen;
        }
        return idx;
    }

    private View cell(JSONObject b, int coverColorRes) {
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
        box.setLayoutParams(boxLp);   // the inter-card gutter is set on the row's LayoutParams (render)

        // Colored cover (placeholder; real covers load later from cover_url).
        LinearLayout cover = new LinearLayout(requireContext());
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setPadding(px(12), px(14), px(12), px(14));
        int color = ContextCompat.getColor(requireContext(), coverColorRes);
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
            cover.addView(band(R.color.k2go_band_library,
                    getString(wizard ? R.string.k2go_books_added : R.string.k2go_books_in_library)));
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

    /**
     * A state pill under the title. ADFA-5248: the pill is a light (cream) surface so it separates
     * from ANY of the 6 covers (all medium/dark) — the old colored fill matched the same-hue covers
     * (green band on a green cover) and vanished. The semantic hue now lives in the TEXT ({@code
     * colorRes}), which reads darkly on the cream pill.
     */
    private TextView band(int colorRes, String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), px(4), px(12), px(4));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        GradientDrawable d = new GradientDrawable();
        d.setColor(ContextCompat.getColor(requireContext(), R.color.k2go_band_surface));
        d.setCornerRadius(px(8));
        d.setStroke(px(1), 0x1A000000);   // subtle dark hairline to define the pill edge on any cover
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
            // ADFA-4910: the landing now leads to a Confirm/review step, not a direct add.
            addBtn.setText(n > 0 ? getString(R.string.k2go_books_review_fmt, n) : getString(R.string.k2go_books_add_none));
        }
        if (wizard) { downloadsLink.setVisibility(View.GONE); return; }   // no live downloads pre-install
        boolean active = BooksDownloadService.hasSession();
        downloadsLink.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) downloadsLink.setText(getString(R.string.k2go_books_view_downloads));
    }

    /** ADFA-4910: hand the current selection to the activity cart and open the Confirm/review
     *  screen. Confirm is the single place that banks (wizard) or downloads (live), so both flows
     *  share one review step. The picks stay in {@code selected} so returning here keeps the state. */
    private void reviewSelection() {
        if (selected.isEmpty()) return;
        if (!(getActivity() instanceof SetupLibraryActivity)) return;
        LinkedHashMap<String, String[]> cart = new LinkedHashMap<>();
        for (JSONObject b : selected.values()) {
            cart.put(b.optString("gutenberg_id", ""), new String[]{
                    b.optString("title", ""), b.optString("author", ""), b.optString("download_url", "")});
        }
        SetupLibraryActivity a = (SetupLibraryActivity) getActivity();
        a.setBooksCart(cart);
        a.openBooksConfirm();
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
