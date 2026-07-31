/*
 * ============================================================================
 * Name        : BackupRestoreFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4952 / 4961. Backup & restore bifurcation — title + description + "what do you
 *               want to do?" with two icon cards (Back up / Restore). Tapping a card IS the Start: it
 *               opens BackupJobFragment, which auto-opens the file picker. ADFA-4961: after an op
 *               finishes and its screen taps Finish, this bifurcation shows the module-index-style
 *               "returning to Home" countdown with a Cancel (armed via armReturnCountdown()). Hosted in
 *               SetupLibraryActivity.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Intent;
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
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;

public class BackupRestoreFragment extends Fragment {

    // ADFA-4961: one-shot flag set by BackupJobFragment's Finish, consumed here to run the "returning
    // to Home" countdown. Process-scoped: the bifurcation is recreated when the job screen pops.
    private static boolean sReturnPending = false;
    public static void armReturnCountdown() { sReturnPending = true; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable homeRunnable;

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
        cards.addView(card(R.drawable.ic_archive, R.string.k2go_br_backup_title, R.string.k2go_br_backup_sub,
                v -> open(BackupJobFragment.MODE_BACKUP)));
        cards.addView(card(R.drawable.ic_unarchive, R.string.k2go_br_restore_title, R.string.k2go_br_restore_sub,
                v -> open(BackupJobFragment.MODE_RESTORE)));

        // ADFA-4961: returning from a finished op → index-style "returning to Home" countdown + Cancel.
        if (sReturnPending) {
            sReturnPending = false;
            col.addView(buildReturnBar());
            homeRunnable = this::goHome;
            main.postDelayed(homeRunnable, 3000L);
        }

        return scroll;
    }

    private void open(String jobMode) {
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).openBackupJob(jobMode);
        }
    }

    private View buildReturnBar() {
        LinearLayout bar = new LinearLayout(requireContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.k2go_info_bg);
        bar.setPadding(px(14), px(12), px(14), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(16);
        bar.setLayoutParams(lp);

        TextView msg = new TextView(requireContext());
        msg.setText(getString(R.string.k2go_bj_returning));
        msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        msg.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        bar.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = new TextView(requireContext());
        cancel.setText(getString(R.string.cancel));
        cancel.setTypeface(cancel.getTypeface(), android.graphics.Typeface.BOLD);
        cancel.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        cancel.setPadding(px(12), px(4), px(4), px(4));
        cancel.setOnClickListener(v -> {
            if (homeRunnable != null) main.removeCallbacks(homeRunnable);
            bar.setVisibility(View.GONE);
        });
        bar.addView(cancel);
        return bar;
    }

    private void goHome() {
        if (!isAdded()) return;
        startActivity(new Intent(requireContext(), LibraryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_library));
        requireActivity().finish();
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

    private View card(int iconRes, int titleRes, int subRes, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(16), px(16), px(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(onClick);

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconRes);
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(28), px(28));
        ilp.rightMargin = px(14);
        row.addView(icon, ilp);

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(requireContext());
        t.setText(getString(titleRes));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        textCol.addView(t);

        TextView sub = new TextView(requireContext());
        sub.setText(getString(subRes));
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(4);
        textCol.addView(sub, slp);
        return row;
    }

    @Override public void onDestroyView() {
        if (homeRunnable != null) main.removeCallbacks(homeRunnable);
        super.onDestroyView();
    }
}
