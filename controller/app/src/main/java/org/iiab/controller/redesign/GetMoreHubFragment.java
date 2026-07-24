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
 *               ADFA-4850: cards are conditional on the backing module being present. You
 *               can only add a content type if its server exists to receive it, so a
 *               conditional card (Books → Calibre-Web) is shown only once its endpoint
 *               answers. Kiwix and Maps ship by default, so their cards are always shown;
 *               Courses (Kolibri) is still a TBD placeholder.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

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

    /** One content type on the hub. {@code key} is what SetupLibraryActivity routes on (and,
     *  for conditional cards, the server endpoint to probe); {@code note} is the second (bold)
     *  line, {@code amber} tints it (e.g. "In-app: TBD"); {@code conditional} cards appear only
     *  once their endpoint answers (the module is installed). */
    private static final class Item {
        final String key; final int icon; final int title; final int desc; final int note;
        final boolean amber; final boolean conditional;
        Item(String k, int i, int t, int d, int n, boolean a, boolean c) {
            key = k; icon = i; title = t; desc = d; note = n; amber = a; conditional = c;
        }
    }

    private static final Item[] ITEMS = {
            new Item("wikipedia", R.drawable.ic_card_wikipedia, R.string.k2go_gm_wikipedia_title, R.string.k2go_gm_wikipedia_desc, R.string.k2go_gm_wikipedia_note, false, false),
            new Item("books",     R.drawable.ic_card_book,      R.string.k2go_gm_books_title,     R.string.k2go_gm_books_desc,     R.string.k2go_gm_books_note,     false, true),
            new Item("maps",      R.drawable.ic_card_maps,      R.string.k2go_gm_maps_title,      R.string.k2go_gm_maps_desc,      R.string.k2go_gm_maps_note,      false, false),
            new Item("courses",   R.drawable.ic_card_courses,   R.string.k2go_gm_courses_title,   R.string.k2go_gm_courses_desc,   R.string.k2go_gm_courses_note,   true,  false),
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> availableConditional = new HashSet<>();
    private LayoutInflater inflater;
    private LinearLayout host;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        this.inflater = inflater;
        View root = inflater.inflate(R.layout.fragment_k2go_getmore_hub, container, false);
        host = root.findViewById(R.id.k2go_gm_cards);
        buildCards();
        probeConditional();
        return root;
    }

    /** Probe each conditional card's endpoint; reveal it (and rebuild the grid) once it answers. */
    private void probeConditional() {
        for (final Item it : ITEMS) {
            if (!it.conditional) continue;
            AppExecutors.get().io().execute(() -> {
                final boolean ok = reachable(it.key);
                main.post(() -> {
                    if (!isAdded() || !ok) return;
                    if (availableConditional.add(it.key)) buildCards();
                });
            });
        }
    }

    /** The items currently shown: all non-conditional, plus conditional ones confirmed available. */
    private List<Item> visibleItems() {
        List<Item> out = new ArrayList<>();
        for (Item it : ITEMS) {
            if (!it.conditional || availableConditional.contains(it.key)) out.add(it);
        }
        return out;
    }

    private void buildCards() {
        if (host == null) return;
        host.removeAllViews();
        List<Item> items = visibleItems();
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
                    if (getActivity() instanceof SetupLibraryActivity) {
                        ((SetupLibraryActivity) getActivity()).openContentType(it.key, getString(it.title));
                    }
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
