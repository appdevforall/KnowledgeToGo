/*
 * ============================================================================
 * Name        : BackupRestoreFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4952. Backup & restore for the redesign — a Clone-style intro (title + description
 *               + two cards: Back up / Restore). One step per direction (SAF to/from an external file).
 *               Hosted in SetupLibraryActivity, which owns the ServerController used to stop the
 *               environment before a job (a static rootfs) and boot it after; coordinated via
 *               EnvironmentLock so it never overlaps a module install / clone / another backup.
 *               This increment wires BACKUP (SAF CreateDocument + streaming tar|gzip); RESTORE next.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.backup.domain.BackupEngine;
import org.iiab.controller.env.EnvironmentLock;
import org.iiab.controller.ui.dialog.BrandDialog;
import org.iiab.controller.util.AppExecutors;
import org.iiab.controller.util.Snackbars;

import java.io.OutputStream;

public class BackupRestoreFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout cards;
    private TextView status;
    private org.iiab.controller.util.EllipsisAnimator statusDots;
    private boolean busy = false;

    // Registered at construction (before STARTED) as required by the Activity Result API.
    private final ActivityResultLauncher<String> createDoc =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
                if (uri != null) runBackup(uri);
                else clearBusyIntent();   // user cancelled the file picker
            });

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
        back.setOnClickListener(v -> { if (!busy) requireActivity().getOnBackPressedDispatcher().onBackPressed(); });
        col.addView(back);

        col.addView(heading(getString(R.string.k2go_br_title), true,
                com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, R.color.k2go_ink, 0));
        col.addView(heading(getString(R.string.k2go_br_desc), false,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.k2go_muted, 6));
        col.addView(heading(getString(R.string.k2go_br_prompt), true,
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink, 20));

        cards = new LinearLayout(requireContext());
        cards.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = px(10);
        col.addView(cards, clp);
        cards.addView(card(R.string.k2go_br_backup_title, R.string.k2go_br_backup_sub, v -> onBackUp()));
        cards.addView(card(R.string.k2go_br_restore_title, R.string.k2go_br_restore_sub, v -> onRestore()));

        status = new TextView(requireContext());
        status.setGravity(Gravity.CENTER);
        status.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_amber_text));
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(16);
        col.addView(status, slp);
        statusDots = new org.iiab.controller.util.EllipsisAnimator(status);

        return scroll;
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

    // ---- Backup ----
    private void onBackUp() {
        if (busy) return;
        if (EnvironmentLock.isHeld(requireContext())) { Snackbars.make(requireView(), R.string.k2go_install_busy).show(); return; }
        new BrandDialog(requireContext())
                .setTitle(getString(R.string.k2go_br_backup_title))
                .setMessage(getString(R.string.k2go_br_backup_warn))
                .setPositive(R.string.k2go_br_start, BrandDialog.Role.PRIMARY,
                        () -> { busy = true; createDoc.launch(BackupEngine.suggestedFileName(requireContext())); })
                .setNegative(R.string.cancel, null)
                .show();
    }

    /** File chosen: acquire the lock, stop the environment, stream the backup, then boot it back. */
    private void runBackup(Uri uri) {
        if (!isAdded()) return;
        EnvironmentLock.acquire(requireContext(), EnvironmentLock.Owner.BACKUP);
        final SetupLibraryActivity host = (SetupLibraryActivity) requireActivity();
        host.enableSystemProtection();                    // watchdog: don't let Android kill the job
        setBusyUi(getString(R.string.k2go_br_status_stopping));
        host.server().stopEnvironment(() -> {             // services down → static rootfs
            if (!isAdded()) { finishBackup(host, false); return; }
            setStatus(getString(R.string.k2go_br_status_backing));
            AppExecutors.get().io().execute(() -> {
                boolean ok;
                try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
                    ok = os != null && BackupEngine.streamBackup(requireContext(), os);
                } catch (Exception e) {
                    ok = false;
                }
                final boolean success = ok;
                main.post(() -> finishBackup(host, success));
            });
        });
    }

    private void finishBackup(SetupLibraryActivity host, boolean ok) {
        // Bring the environment back up regardless (the server was ours to restart), then release.
        if (host.server() != null) host.server().startEnvironment();
        EnvironmentLock.release(host);
        busy = false;
        clearBusyUi();
        if (isAdded()) {
            Snackbars.make(requireView(), ok ? R.string.k2go_br_backup_done : R.string.k2go_br_backup_failed).show();
        }
    }

    private void onRestore() {
        if (busy) return;
        Snackbars.make(requireView(), R.string.k2go_br_soon).show();   // ADFA-4952: restore wired next increment
    }

    // ---- busy UI ----
    private void setBusyUi(String text) {
        if (cards != null) { cards.setAlpha(0.4f); }
        setStatus(text);
    }
    private void setStatus(String text) {
        if (status == null) return;
        status.setVisibility(View.VISIBLE);
        if (statusDots != null) statusDots.start(text);
    }
    private void clearBusyUi() {
        if (cards != null) cards.setAlpha(1f);
        if (statusDots != null) statusDots.stop();
        if (status != null) status.setVisibility(View.GONE);
    }
    /** SAF picker cancelled before any work — undo the pending-busy intent set in the confirm dialog. */
    private void clearBusyIntent() { busy = false; }

    @Override public void onDestroyView() {
        if (statusDots != null) statusDots.stop();
        super.onDestroyView();
    }
}
