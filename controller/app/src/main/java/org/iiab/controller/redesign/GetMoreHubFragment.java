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
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
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

public class GetMoreHubFragment extends Fragment {

    /** One content type on the hub. {@code key} is what SetupLibraryActivity routes on;
     *  {@code note} is the second (bold) line, {@code amber} tints it (e.g. "In-app: TBD"). */
    private static final class Item {
        final String key; final int icon; final int title; final int desc; final int note; final boolean amber;
        Item(String k, int i, int t, int d, int n, boolean a) { key = k; icon = i; title = t; desc = d; note = n; amber = a; }
    }

    private static final Item[] ITEMS = {
            new Item("wikipedia", R.drawable.ic_card_wikipedia, R.string.k2go_gm_wikipedia_title, R.string.k2go_gm_wikipedia_desc, R.string.k2go_gm_wikipedia_note, false),
            new Item("books",     R.drawable.ic_card_book,      R.string.k2go_gm_books_title,     R.string.k2go_gm_books_desc,     R.string.k2go_gm_books_note,     false),
            new Item("maps",      R.drawable.ic_card_maps,      R.string.k2go_gm_maps_title,      R.string.k2go_gm_maps_desc,      R.string.k2go_gm_maps_note,      false),
            new Item("courses",   R.drawable.ic_card_courses,   R.string.k2go_gm_courses_title,   R.string.k2go_gm_courses_desc,   R.string.k2go_gm_courses_note,   true),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_getmore_hub, container, false);
        LinearLayout host = root.findViewById(R.id.k2go_gm_cards);
        buildCards(inflater, host);
        return root;
    }

    private void buildCards(LayoutInflater inflater, LinearLayout host) {
        host.removeAllViews();
        final int cardH = getResources().getDimensionPixelSize(R.dimen.k2go_gm_card_height);
        for (int i = 0; i < ITEMS.length; i += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            host.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int k = i; k < i + 2 && k < ITEMS.length; k++) {
                final Item it = ITEMS[k];
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
        }
    }
}
