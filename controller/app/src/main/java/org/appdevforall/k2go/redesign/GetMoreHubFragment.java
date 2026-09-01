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
package org.appdevforall.k2go.redesign;

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

import org.appdevforall.k2go.InstallationPlanner;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.util.AppExecutors;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GetMoreHubFragment extends Fragment {

    /** One content type on the hub. {@code key} is what SetupLibraryActivity routes on;
     *  {@code endpoint} is the server path probed to decide whether the module is installed
     *  (so the card shows); {@code note} is the second (bold) line.
     *
     *  <p>ADFA-5074: there was an {@code amber} flag here that tinted the note as a warning.
     *  Courses was the only card using it, to mark "In-app: TBD" as not ready. The card now
     *  names what it offers, so the tint had stopped meaning "unfinished" and started meaning
     *  "something is wrong with this one". A future not-ready card should reintroduce the flag
     *  deliberately rather than inherit a colour nobody chose. */
    private static final class Item {
        final String key; final String endpoint; final int icon;
        final int title; final int desc; final int note;
        Item(String k, String e, int i, int t, int d, int n) {
            key = k; endpoint = e; icon = i; title = t; desc = d; note = n;
        }
    }

    private static final Item[] ITEMS = {
            new Item("wikipedia", "kiwix",   R.drawable.ic_card_wikipedia, R.string.k2go_gm_wikipedia_title, R.string.k2go_gm_wikipedia_desc, R.string.k2go_gm_wikipedia_note),
            new Item("books",     "books",   R.drawable.ic_card_book,      R.string.k2go_gm_books_title,     R.string.k2go_gm_books_desc,     R.string.k2go_gm_books_note),
            new Item("maps",      "maps",    R.drawable.ic_card_maps,      R.string.k2go_gm_maps_title,      R.string.k2go_gm_maps_desc,      R.string.k2go_gm_maps_note),
            new Item("courses",   "kolibri", R.drawable.ic_card_courses,   R.string.k2go_gm_courses_title,   R.string.k2go_gm_courses_desc,   R.string.k2go_gm_courses_note),
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
        // ADFA-4954: Kolibri ships in Full and Standard, not in Basic, so Courses is offered
        // for those two. Offering it in Basic would let the user pick content that would have
        // nowhere to land, and the post-install drain would fail with nothing to explain it.
        InstallationPlanner.Tier tier = (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getSelectedTier() : null;
        if (tier == InstallationPlanner.Tier.FULL || tier == InstallationPlanner.Tier.STANDARD) {
            available.add("courses");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (wizard && getView() != null) refreshStorage(getView());   // picks may have changed
        // ADFA-5074: keep asking while this screen is up. A platform can arrive after the
        // hub opened — Kolibri is Python and takes noticeably longer to answer than the
        // rest — and a card that was absent stayed absent until the user left and came
        // back. Home has always polled; this screen never did, for no reason beyond which
        // one was written first.
        if (!wizard) main.post(rescan);
    }

    @Override
    public void onPause() {
        super.onPause();
        main.removeCallbacks(rescan);   // no probing a screen nobody is looking at
    }

    /** Re-probe while the hub is on screen, so a platform that finishes starting appears. */
    private final Runnable rescan = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            // Only the ones still missing: a card that answered does not un-answer, and
            // re-probing all of them every few seconds would be four requests for nothing.
            // And only when nothing is already in flight, so the pass on open (probeAll)
            // and this one do not ask the same endpoint twice.
            if (probesPending == 0 && available.size() < ITEMS.length) probeMissing();
            main.postDelayed(this, RESCAN_MS);
        }
    };

    /** ADFA-5074: how often the hub re-checks for a platform that was still starting. */
    private static final long RESCAN_MS = 3000L;

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
                        org.appdevforall.k2go.util.ByteFormatter.humanGb(used),
                        org.appdevforall.k2go.util.ByteFormatter.humanGb(systemGb),
                        org.appdevforall.k2go.util.ByteFormatter.humanGb(picksGb),
                        org.appdevforall.k2go.util.ByteFormatter.humanGb(freeAfter)));
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
        // ADFA-4954: the banked Courses order carries real catalog bytes, so it needs no estimate.
        gb += org.appdevforall.k2go.kolibri.data.KolibriWishlist.totalBytes(requireContext())
                / (1024.0 * 1024.0 * 1024.0);
        return gb;
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
            probe(it);
        }
    }

    /**
     * ADFA-5074: probe only the platforms that have not answered yet.
     *
     * <p>Called on a timer while the hub is visible. {@code probesPending} is what makes
     * the empty state say "checking…" rather than "nothing available", so it is raised by
     * exactly the number of probes about to run — a rescan that reused the first count
     * would leave the screen claiming to be checking forever.
     */
    private void probeMissing() {
        List<Item> missing = new ArrayList<>();
        for (Item it : ITEMS) if (!available.contains(it.key)) missing.add(it);
        if (missing.isEmpty()) return;
        probesPending += missing.size();
        for (final Item it : missing) {
            probe(it);
        }
    }

    private void probe(final Item it) {
        AppExecutors.get().io().execute(() -> {
            final boolean ok = reachable(it.endpoint);
            main.post(() -> {
                // ADFA-5074: decremented BEFORE the isAdded() check. The counter describes
                // probes launched, not views present, and the rescan will not run while it
                // reads above zero — a probe that came back to a detached fragment used to
                // leave it high, which would have turned the rescan off for good and left
                // the empty state saying "checking…" forever.
                probesPending--;
                if (!isAdded()) return;
                if (ok) available.add(it.key);
                buildCards();
            });
        });
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

        if (items.isEmpty()) {
            TextView msg = new TextView(requireContext());
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, dp(24), 0, dp(24));
            msg.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            msg.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            // ADFA-5061: three cases, not two. This read "still checking, or nothing
            // installed", and a stopped box fell into the second — every endpoint failed to
            // answer, so the screen concluded the modules were not there. Found on device
            // by stopping the engine and opening Get More: "No content modules are
            // installed yet" over a box that has all of them.
            //
            // Same mistake as the courses confirm screen, one floor up, and the same fix in
            // spirit: an endpoint that does not answer has said nothing. Here the box's own
            // state settles it, and it costs no request — ServerStateRepository is the
            // cached observation the server poll already maintains.
            //
            // No observation yet is treated as stopped rather than as empty. It is a guess
            // either way, and this is the guess that sends the user somewhere useful.
            // Only distinguishes a stopped box from an empty one. A box that is up while every
            // platform is too slow to answer still lands on "nothing installed" — the same
            // lie, for the harder-to-see case. Fixing that properly means one shared presence
            // answer rather than a fourth reading here; recorded in ADR-5061 item 2d.
            int empty = org.appdevforall.k2go.system.data.SystemFactsReader.serverAnswering()
                    ? R.string.k2go_gm_none : R.string.k2go_gm_server_off;
            msg.setText(probesPending > 0 ? R.string.k2go_gm_checking : empty);
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
                note.setTextColor(androidx.core.content.ContextCompat.getColor(
                        requireContext(), R.color.k2go_muted));
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

    // ADFA-5061: this asked ServerStateRepository directly, in two lines identical to the
    // ones inside SystemFactsReader — and said so in its own javadoc, which is the shape of
    // divergence decision 9 exists to end. It calls the reader now.

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
