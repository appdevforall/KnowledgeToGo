/*
 * ============================================================================
 * Name        : BackupRestoreFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4952. Backup & restore intro — a Clone-style screen: title + description +
 *               "what do you want to do?" with two cards (Back up / Restore). Each card opens its own
 *               dedicated job screen (BackupJobFragment) with the working Lottie + status. Hosted in
 *               SetupLibraryActivity (which owns the ServerController + coordinates via EnvironmentLock).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;

public class BackupRestoreFragment extends Fragment {

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(20), px(18), px(20), px(24));
        scroll.addView(col, new NestedScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView back = new TextView(requireContext());
        back.setText(getString(R.string.k2go_back_link));
        back.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        back.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        back.setPadding(0, 0, 0, px(12));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        col.addView(back);

        col.addView(heading(getString(R.string.k2go_br_title), true,
                com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, R.color.k2go_ink, 0));
        col.addView(heading(getString(R.string.k2go_br_desc), false,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.k2go_muted, 6));
        col.addView(heading(getString(R.string.k2go_br_prompt), true,
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink, 20));

        LinearLayout cards = new LinearLayout(requireContext());
        cards.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = px(10);
        col.addView(cards, clp);
        cards.addView(card(R.string.k2go_br_backup_title, R.string.k2go_br_backup_sub,
                v -> open(BackupJobFragment.MODE_BACKUP)));
        cards.addView(card(R.string.k2go_br_restore_title, R.string.k2go_br_restore_sub,
                v -> open(BackupJobFragment.MODE_RESTORE)));

        return scroll;
    }

    private void open(String jobMode) {
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).openBackupJob(jobMode);
        }
    }

    private TextView heading(String text, boolean bold, int appearance, int colorRes, int topDp) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        t.setTextAppearance(appearance);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(topDp);
        t.setLayoutParams(lp);
        return t;
    }

    private View card(int titleRes, int subRes, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(16), px(16), px(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(onClick);

        TextView t = new TextView(requireContext());
        t.setText(getString(titleRes));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        row.addView(t);

        TextView sub = new TextView(requireContext());
        sub.setText(getString(subRes));
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(4);
        row.addView(sub, slp);
        return row;
    }
}
