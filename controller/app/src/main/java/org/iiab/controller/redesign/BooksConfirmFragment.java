/*
 * ============================================================================
 * Name        : BooksConfirmFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4910. Books Confirm — a review step between the Books landing and the
 *               download/bank action, mirroring ZimConfirmFragment. Lists the picked books
 *               (title + author), a total count, an honest note, and a primary button. Books
 *               carry no per-item size, so this shows a count (not GB). In the wizard the button
 *               banks the picks to BooksWishlist and returns to the Get More hub; live, it hands
 *               the picks to BooksDownloadService and opens the downloads screen. Reads the
 *               selection cart from SetupLibraryActivity.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.graphics.Typeface;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.iiab.controller.R;

public class BooksConfirmFragment extends Fragment {

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    /** Selection cart from the activity: gutenberg_id -> {title, author, download_url}. */
    private LinkedHashMap<String, String[]> cart() {
        return (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getBooksCart() : new LinkedHashMap<>();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_books_confirm, container, false);

        TextView back = root.findViewById(R.id.k2go_bconf_back);
        back.setText("‹ " + getString(R.string.k2go_books_back_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        LinkedHashMap<String, String[]> cart = cart();
        LinearLayout box = root.findViewById(R.id.k2go_bconf_list);
        for (Map.Entry<String, String[]> e : cart.entrySet()) {
            String[] v = e.getValue();
            String title = v != null && v.length > 0 ? v[0] : "";
            String author = v != null && v.length > 1 ? v[1] : "";
            box.addView(row(title, author, false));
            box.addView(divider());
        }
        box.addView(row(getString(R.string.k2go_books_total_fmt, cart.size()), "", true));

        // ADFA-5061: asked of the system, not of the door. Was isBooksWizard(), a field on
        // the activity that did not survive a recreation. Resolved once here so the label
        // and the action are the same answer, and re-derived whenever the view is rebuilt.
        final boolean banks = org.iiab.controller.system.data.ContentDoor.banks(
                requireContext(), org.iiab.controller.system.domain.ContentType.BOOKS,
                SetupLibraryActivity.replacingSystem(this));
        Button add = root.findViewById(R.id.k2go_bconf_add);
        add.setText(getString(banks ? R.string.k2go_books_add_setup_fmt : R.string.k2go_books_add_fmt, cart.size()));
        add.setEnabled(!cart.isEmpty());
        add.setOnClickListener(v -> {
            if (!(getActivity() instanceof SetupLibraryActivity)) return;
            SetupLibraryActivity a = (SetupLibraryActivity) getActivity();
            if (banks) a.booksWizardConfirm();   // no box yet: bank it
            else if (!DashboardRebuild.blockedByUpdate(v)) a.startBooksDownload();   // live: download now (ADFA-5333)
        });

        return root;
    }

    private View row(String name, String sub, boolean totalRow) {
        LinearLayout r = new LinearLayout(requireContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(10), 0, px(10));

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(requireContext());
        n.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        n.setText(name);
        n.setTextColor(ContextCompat.getColor(requireContext(), totalRow ? R.color.k2go_teal : R.color.k2go_ink));
        if (totalRow) n.setTypeface(n.getTypeface(), Typeface.BOLD);
        text.addView(n);
        if (!sub.isEmpty()) {
            TextView subv = new TextView(requireContext());
            subv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            subv.setText(sub);
            subv.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            text.addView(subv);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private View divider() {
        View d = new View(requireContext());
        d.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        d.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.k2go_hairline));
        return d;
    }
}
