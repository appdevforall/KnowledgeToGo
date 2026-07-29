/*
 * ============================================================================
 * Name        : GetMoreHubFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (Get More redesign). The content-catalog hub: one card per
 *               content type (Wikipedia & ZIM / Books / Maps / Courses), each opening
 *               its own content-type screen. Hosted in SetupLibraryActivity and reached
 *               from the Library "Get more" entry (and, later, the wizard content step —
 *               the same screens, two doors). Cards are built into a 2-column grid,
 *               mirroring LibraryHomeFragment so translated labels never skew the layout.
 *
 *               ADFA-4850: every card is gated on its backing module being present. You can
 *               only add a content type if its server exists to receive it, so a card shows
 *               only once its endpoint answers (Wikipedia→kiwix, Books→calibre-web at /books,
 *               Maps→maps, Courses→kolibri). The hub probes all endpoints on open and reveals
 *               the ones that respond; nothing installed → an empty-state message.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;
import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GetMoreHubFragment extends Fragment {

    /** One content type on the hub. {@code key} is what SetupLibraryActivity routes on;
     *  {@code endpoint} is the server path probed to decide whether the module is installed
     *  (so the card shows); {@code note} is the second (bold) line, {@code amber} tints it. */
    private static final class Item {
        final String key; final String endpoint; final int icon;
        final int title; final int desc; final int note; final boolean amber;
        Item(String k, String e, int i, int t, int d, int n, boolean a) {
            key = k; endpoint = e; icon = i; title = t; desc = d; note = n; amber = a;
        }
    }

    private static final Item[] ITEMS = {
            new Item("wikipedia", "kiwix",   R.drawable.ic_card_wikipedia, R.string.k2go_gm_wikipedia_title, R.string.k2go_gm_wikipedia_desc, R.string.k2go_gm_wikipedia_note, false),
            new Item("books",     "books",   R.drawable.ic_card_book,      R.string.k2go_gm_books_title,     R.string.k2go_gm_books_desc,     R.string.k2go_gm_books_note,     false),
            new Item("maps",      "maps",    R.drawable.ic_card_maps,      R.string.k2go_gm_maps_title,      R.string.k2go_gm_maps_desc,      R.string.k2go_gm_maps_note,      false),
            new Item("courses",   "kolibri", R.drawable.ic_card_courses,   R.string.k2go_gm_courses_title,   R.string.k2go_gm_courses_desc,   R.string.k2go_gm_courses_note,   true),
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> available = new HashSet<>();
    private int probesPending = 0;
    private LayoutInflater inflater;
    private LinearLayout host;
    private boolean wizard;

    /** Wizard (pre-install) mode: availability comes from the chosen tier's plan, not a live probe,
     *  and cards open the offline/wishlist screens instead of the live ones. */
    public static GetMoreHubFragment newInstance(boolean wizard) {
        GetMoreHubFragment f = new GetMoreHubFragment();
        Bundle b = new Bundle();
        b.putBoolean("wizard", wizard);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        this.inflater = inflater;
        View root = inflater.inflate(R.layout.fragment_k2go_getmore_hub, container, false);
        host = root.findViewById(R.id.k2go_gm_cards);
        wizard = getArguments() != null && getArguments().getBoolean("wizard", false);
        if (wizard) {
            // Same hub, worn as the wizard's content step: retitle it and offer Continue.
            TextView title = root.findViewById(R.id.k2go_gm_title);
            if (title != null) title.setText(R.string.k2go_setup_library_title);
            View cont = root.findViewById(R.id.k2go_gm_continue);
            cont.setVisibility(View.VISIBLE);
            cont.setOnClickListener(v -> {
                if (getActivity() instanceof SetupLibraryActivity) ((SetupLibraryActivity) getActivity()).startWizardInstall();
            });
            root.findViewById(R.id.k2go_gm_wizard_header).setVisibility(View.VISIBLE);
            StepSpine.render(root.findViewById(R.id.k2go_gm_spine),
                    new StepSpine.Step("1", getString(R.string.k2go_lbl_system), false, true),
                    new StepSpine.Step("2", getString(R.string.k2go_lbl_content), true, false));
            refreshStorage(root);
            computeWizardAvailability();   // synchronous, tier-based (no server yet)
        } else {
            probeAll();
        }
        buildCards();   // live mode shows "checking…" until probes resolve
        return root;
    }

    /** Pre-install: a card is offered based on the chosen tier, not a live probe. Books ships only
     *  in Full (Calibre-Web); Wikipedia and Maps ship in every tier; Courses (Kolibri) is TBD. */
    private void computeWizardAvailability() {
        available.clear();
        available.add("wikipedia");
        available.add("maps");
        boolean full = (getActivity() instanceof SetupLibraryActivity)
                && ((SetupLibraryActivity) getActivity()).getSelectedTier() == InstallationPlanner.Tier.FULL;
        if (full) available.add("books");
        // courses: hidden until determined
    }

    @Override
    public void onResume() {
        super.onResume();
        if (wizard && getView() != null) refreshStorage(getView());   // picks may have changed
    }

    /** Wizard-only storage projection: System (tier OS) + Your picks (wishlists) vs device free. */
    private void refreshStorage(View root) {
        if (root == null || !isAdded()) return;
        InstallationPlanner.Tier tier = (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getSelectedTier() : InstallationPlanner.Tier.STANDARD;
        double systemGb = InstallationPlanner.fallbackOsSizeGb(tier);
        double picksGb = picksGb();
        double total = StorageInfo.totalGb();
        double used = StorageInfo.usedGb();
        double freeAfter = Math.max(0, StorageInfo.freeGb() - systemGb - picksGb);
        if (total <= 0) total = used + systemGb + picksGb + freeAfter + 0.01;
        LinearLayout bar = root.findViewById(R.id.k2go_gm_bar);
        bar.setWeightSum((float) total);
        setW(root.findViewById(R.id.k2go_gm_bar_used), (float) used);
        setW(root.findViewById(R.id.k2go_gm_bar_system), (float) systemGb);
        setW(root.findViewById(R.id.k2go_gm_bar_picks), (float) picksGb);
        setW(root.findViewById(R.id.k2go_gm_bar_free), (float) freeAfter);
        ((TextView) root.findViewById(R.id.k2go_gm_legend)).setText(
                getString(R.string.k2go_legend_your_picks,
                        human(used), human(systemGb), human(picksGb), human(freeAfter)));
    }

    /** GB the wizard picks will add: ZIM by real catalog bytes; maps by the banked catalog size;
     *  books are tiny EPUBs (~few MB, estimated since the catalog has no per-book size). */
    private double picksGb() {
        double gb = 0;
        org.json.JSONArray z = ZimWishlist.all(requireContext());
        for (int i = 0; i < z.length(); i++) {
            org.json.JSONObject o = z.optJSONObject(i);
            if (o != null) gb += o.optLong("bytes", 0) / (1024.0 * 1024.0 * 1024.0);
        }
        gb += MapsWishlist.mb(requireContext()) / 1024.0;     // ADFA-4910: count the banked maps size
        gb += BooksWishlist.size(requireContext()) * 0.003;
        return gb;
    }

    /** ADFA-4910: adaptive size unit so small picks aren't rounded to "0.0 GB". Steps up at ~1024:
     *  KB < 1 MB, MB < 1 GB, else GB. Input is in GB. */
    private String human(double gb) {
        double mb = gb * 1024.0;
        double kb = mb * 1024.0;
        // Pick the unit from the value that would round up, so a boundary never shows "1024 MB".
        if (mb >= 1023.5) return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0);
        if (kb >= 1023.5) return String.format(java.util.Locale.US, "%.0f MB", mb);
        return String.format(java.util.Locale.US, "%.0f KB", Math.max(0, kb));
    }

    private void setW(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    /** Probe every card's endpoint; reveal the ones that answer (module installed). */
    private void probeAll() {
        probesPending = ITEMS.length;
        for (final Item it : ITEMS) {
            AppExecutors.get().io().execute(() -> {
                final boolean ok = reachable(it.endpoint);
                main.post(() -> {
                    if (!isAdded()) return;
                    if (ok) available.add(it.key);
                    probesPending--;
                    buildCards();
                });
            });
        }
    }

    /** The items to show: those whose module answered, in the declared order. */
    private List<Item> visibleItems() {
        List<Item> out = new ArrayList<>();
        for (Item it : ITEMS) if (available.contains(it.key)) out.add(it);
        return out;
    }

    private void buildCards() {
        if (host == null) return;
        host.removeAllViews();
        List<Item> items = visibleItems();

        if (items.isEmpty()) {                       // still checking, or nothing installed
            TextView msg = new TextView(requireContext());
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, dp(24), 0, dp(24));
            msg.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            msg.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            msg.setText(probesPending > 0 ? R.string.k2go_gm_checking : R.string.k2go_gm_none);
            host.addView(msg);
            return;
        }

        final int cardH = getResources().getDimensionPixelSize(R.dimen.k2go_gm_card_height);
        for (int i = 0; i < items.size(); i += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            host.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int k = i; k < i + 2 && k < items.size(); k++) {
                final Item it = items.get(k);
                View card = inflater.inflate(R.layout.view_k2go_getmore_card, row, false);
                ((ImageView) card.findViewById(R.id.k2go_gm_card_icon)).setImageResource(it.icon);
                ((TextView) card.findViewById(R.id.k2go_gm_card_title)).setText(it.title);
                ((TextView) card.findViewById(R.id.k2go_gm_card_desc)).setText(it.desc);
                TextView note = card.findViewById(R.id.k2go_gm_card_note);
                note.setText(it.note);
                note.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                        it.amber ? R.color.k2go_amber_text : R.color.k2go_muted));
                card.setOnClickListener(v -> {
                    if (!(getActivity() instanceof SetupLibraryActivity)) return;
                    SetupLibraryActivity a = (SetupLibraryActivity) getActivity();
                    if (wizard) a.openWizardContent(it.key, getString(it.title));
                    else a.openContentType(it.key, getString(it.title));
                });
                // Reuse the inflated params so the card's layout_margin (separation) is kept —
                // replacing them with fresh params would drop the margin and glue the cards together.
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                if (lp == null) lp = new LinearLayout.LayoutParams(0, cardH);
                lp.width = 0;
                lp.height = cardH;
                lp.weight = 1f;
                row.addView(card, lp);
            }
            if (i + 1 >= items.size() && items.size() % 2 == 1) { // keep a lone last card half-width
                View pad = new View(requireContext());
                row.addView(pad, new LinearLayout.LayoutParams(0, cardH, 1f));
            }
        }

    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** A module is "installed" for Get More purposes when its server endpoint answers. */
    private static boolean reachable(String endpoint) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BoxEndpoints.BASE + "/" + endpoint + "/");
            c = (HttpURLConnection) u.openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
