/*
 * ============================================================================
 * Name        : BackupRestoreFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4952. Backup & restore intro for the redesign — mirrors the Clone screen: a
 *               title + description + "what do you want to do?" with two cards (Back up / Restore).
 *               One step per direction (SAF to/from an external file); the actual flows are wired in
 *               follow-up increments. Hosted in SetupLibraryActivity (which owns the ServerController
 *               used to stop/restart the environment around a job, and coordinates via EnvironmentLock).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.view.Gravity;
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
import org.iiab.controller.util.Snackbars;

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

        TextView title = new TextView(requireContext());
        title.setText(getString(R.string.k2go_br_title));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        col.addView(title);

        TextView desc = new TextView(requireContext());
        desc.setText(getString(R.string.k2go_br_desc));
        desc.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        desc.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = px(6);
        col.addView(desc, dlp);

        TextView prompt = new TextView(requireContext());
        prompt.setText(getString(R.string.k2go_br_prompt));
        prompt.setTypeface(prompt.getTypeface(), android.graphics.Typeface.BOLD);
        prompt.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        prompt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = px(20);
        plp.bottomMargin = px(10);
        col.addView(prompt, plp);

        col.addView(card(R.string.k2go_br_backup_title, R.string.k2go_br_backup_sub, v -> onBackUp()));
        col.addView(card(R.string.k2go_br_restore_title, R.string.k2go_br_restore_sub, v -> onRestore()));

        return scroll;
    }

    /** A full-width tappable card: bold title + one-line subtitle, matching the module/clone cards. */
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
        sub.setGravity(Gravity.START);
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(4);
        row.addView(sub, slp);
        return row;
    }

    // The flows land in the next increments (backup: SAF + streaming tar|gzip + EnvironmentLock BACKUP
    // + server stop/restart; restore: SAF + validate + extract + EnvironmentLock RESTORE + InstallGuard).
    private void onBackUp() { Snackbars.make(requireView(), R.string.k2go_br_soon).show(); }
    private void onRestore() { Snackbars.make(requireView(), R.string.k2go_br_soon).show(); }
}
