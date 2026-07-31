/*
 * ============================================================================
 * Name        : BackupJobFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4952. Dedicated per-operation screen for Backup OR Restore (mode arg), with the
 *               shared "working" Lottie + an animated status line — the intro's two cards each open one.
 *               Backup: SAF CreateDocument -> stream tar|gzip to the external file (no temp). Restore:
 *               SAF OpenDocument -> copy to temp -> validate (RootfsArchiveValidator) -> confirm
 *               (destructive) -> extract over the rootfs. Both hold the EnvironmentLock and run with the
 *               server stopped (stopEnvironment) and boot it back after (startEnvironment). Restore also
 *               sets InstallGuard so an interrupted (damaging) extract is recoverable.
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

import org.iiab.controller.R;
import org.iiab.controller.TarExtractor;
import org.iiab.controller.backup.domain.BackupEngine;
import org.iiab.controller.env.EnvironmentLock;
import org.iiab.controller.ui.dialog.BrandDialog;
import org.iiab.controller.util.AppExecutors;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

public class BackupJobFragment extends Fragment {

    private static final String ARG_MODE = "mode";
    public static final String MODE_BACKUP = "backup";
    public static final String MODE_RESTORE = "restore";

    public static BackupJobFragment newInstance(String mode) {
        BackupJobFragment f = new BackupJobFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, mode);
        f.setArguments(b);
        return f;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private String mode;
    private LottieAnimationView anim;
    private TextView title, status;
    private Button primary, done;
    private org.iiab.controller.util.EllipsisAnimator statusDots;
    private boolean running = false;

    private final ActivityResultLauncher<String> createDoc =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
                if (uri != null) runBackup(uri); else showReady();
            });
    private final ActivityResultLauncher<String[]> openDoc =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) prepareRestore(uri); else showReady();
            });

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
    private boolean isRestore() { return MODE_RESTORE.equals(mode); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        mode = getArguments() != null ? getArguments().getString(ARG_MODE, MODE_BACKUP) : MODE_BACKUP;

        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(px(24), px(20), px(24), px(24));
        scroll.addView(col, new NestedScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView back = new TextView(requireContext());
        back.setText(getString(R.string.k2go_back_link));
        back.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        back.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        back.setLayoutParams(blp);
        back.setOnClickListener(v -> { if (!running) requireActivity().getOnBackPressedDispatcher().onBackPressed(); });
        col.addView(back);

        anim = new LottieAnimationView(requireContext());
        anim.setAnimation(R.raw.k2go_working_loop);
        anim.setRepeatCount(LottieDrawable.INFINITE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(180), px(180));
        alp.topMargin = px(24);
        col.addView(anim, alp);

        title = new TextView(requireContext());
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = px(16);
        col.addView(title, tlp);

        status = new TextView(requireContext());
        status.setGravity(Gravity.CENTER);
        status.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(10);
        col.addView(status, slp);
        statusDots = new org.iiab.controller.util.EllipsisAnimator(status);

        primary = new Button(requireContext());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = px(28);
        col.addView(primary, plp);
        primary.setOnClickListener(v -> onPrimary());

        done = new Button(requireContext());
        done.setText(getString(R.string.k2go_br_done));
        LinearLayout.LayoutParams donelp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        donelp.topMargin = px(16);
        col.addView(done, donelp);
        done.setVisibility(View.GONE);
        done.setOnClickListener(v -> goLibraryHome());

        showReady();
        return scroll;
    }

    /** Idle "ready to start" state: title + warning + a Start button. */
    private void showReady() {
        running = false;
        if (statusDots != null) statusDots.stop();
        title.setText(getString(isRestore() ? R.string.k2go_br_restore_title : R.string.k2go_br_backup_title));
        status.setText(getString(R.string.k2go_br_backup_warn));   // both stop the server
        status.setVisibility(View.VISIBLE);
        primary.setText(getString(isRestore() ? R.string.k2go_br_restore_pick : R.string.k2go_br_start));
        primary.setVisibility(View.VISIBLE);
        done.setVisibility(View.GONE);
        if (anim != null) { anim.setProgress(0f); anim.pauseAnimation(); }
    }

    private void onPrimary() {
        if (running) return;
        if (EnvironmentLock.isHeld(requireContext())) { setStatusStatic(getString(R.string.k2go_install_busy)); return; }
        if (isRestore()) openDoc.launch(new String[]{"application/gzip", "application/x-gzip", "*/*"});
        else createDoc.launch(BackupEngine.suggestedFileName(requireContext()));
    }

    // ---- BACKUP ----
    private void runBackup(Uri uri) {
        if (!isAdded()) return;
        EnvironmentLock.acquire(requireContext(), EnvironmentLock.Owner.BACKUP);
        final SetupLibraryActivity host = host();
        beginRunning();
        host.enableSystemProtection();
        setStatusAnimated(getString(R.string.k2go_br_status_stopping));
        host.server().stopEnvironment(() -> {
            if (!isAdded()) { restartAndRelease(host, EnvironmentLock.Owner.BACKUP); return; }
            setStatusAnimated(getString(R.string.k2go_br_status_backing));
            AppExecutors.get().io().execute(() -> {
                boolean ok;
                try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
                    ok = os != null && BackupEngine.streamBackup(requireContext(), os);
                } catch (Exception e) { ok = false; }
                final boolean success = ok;
                main.post(() -> {
                    restartAndRelease(host, EnvironmentLock.Owner.BACKUP);
                    finishResult(success, getString(R.string.k2go_br_backup_done), getString(R.string.k2go_br_backup_failed));
                });
            });
        });
    }

    // ---- RESTORE ----
    private void prepareRestore(Uri uri) {
        if (!isAdded()) return;
        beginRunning();
        setStatusAnimated(getString(R.string.k2go_br_status_checking));
        final SetupLibraryActivity host = host();
        AppExecutors.get().io().execute(() -> {
            // Copy the SAF stream to a temp file — the validator and TarExtractor both need a path.
            File temp = new File(requireContext().getCacheDir(), "restore.tar.gz");
            boolean copied = false;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 OutputStream out = new java.io.FileOutputStream(temp)) {
                if (in != null) {
                    byte[] b = new byte[1 << 16]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.flush(); copied = true;
                }
            } catch (Exception ignored) { }
            org.iiab.controller.deploy.data.RootfsArchiveValidator.Result vr = !copied
                    ? org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.UNREADABLE
                    : org.iiab.controller.deploy.data.RootfsArchiveValidator.validate(requireContext(), temp.getAbsolutePath());
            main.post(() -> onValidated(host, temp, vr));
        });
    }

    private void onValidated(SetupLibraryActivity host, File temp, org.iiab.controller.deploy.data.RootfsArchiveValidator.Result vr) {
        boolean ok = vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK
                || vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK_NO_MANIFEST
                || vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK_NO_CHECKSUM;
        if (!ok) {
            if (temp.exists()) temp.delete();
            int msg = vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.WRONG_ARCH ? R.string.install_error_wrong_arch
                    : vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.CORRUPT ? R.string.install_error_corrupt
                    : R.string.install_error_not_rootfs;
            finishResult(false, "", getString(msg));
            return;
        }
        // Valid → confirm the destructive replace.
        new BrandDialog(requireContext())
                .setTitle(getString(R.string.k2go_br_restore_title))
                .setMessage(getString(R.string.k2go_br_restore_warn))
                .setPositive(R.string.k2go_br_restore_confirm, BrandDialog.Role.DESTRUCTIVE, () -> runRestore(host, temp))
                .setNegative(R.string.cancel, () -> { if (temp.exists()) temp.delete(); showReady(); })
                .show();
    }

    private void runRestore(SetupLibraryActivity host, File temp) {
        EnvironmentLock.acquire(requireContext(), EnvironmentLock.Owner.RESTORE);
        org.iiab.controller.InstallGuard.begin(requireContext());   // damage recovery for a killed extract
        host.enableSystemProtection();
        setStatusAnimated(getString(R.string.k2go_br_status_stopping));
        host.server().stopEnvironment(() -> {
            setStatusAnimated(getString(R.string.k2go_br_status_restoring));
            File iiabRootDir = new File(requireContext().getFilesDir(), "rootfs");
            new TarExtractor().startExtraction(requireContext(), temp.getAbsolutePath(), iiabRootDir.getAbsolutePath(), true,
                    new TarExtractor.ExtractionListener() {
                        @Override public void onComplete(String destDir) { main.post(() -> endRestore(host, temp, true)); }
                        @Override public void onError(String error) { main.post(() -> endRestore(host, temp, false)); }
                        @Override public void onProgress(String line) { }
                    });
        });
    }

    private void endRestore(SetupLibraryActivity host, File temp, boolean ok) {
        if (temp.exists()) temp.delete();
        org.iiab.controller.InstallGuard.end(requireContext());
        restartAndRelease(host, EnvironmentLock.Owner.RESTORE);
        finishResult(ok, getString(R.string.k2go_br_restore_done), getString(R.string.k2go_br_restore_failed));
    }

    // ---- shared ----
    private SetupLibraryActivity host() { return (SetupLibraryActivity) requireActivity(); }

    private void restartAndRelease(SetupLibraryActivity host, EnvironmentLock.Owner owner) {
        if (host.server() != null) host.server().startEnvironment();   // boot the (restored) system
        EnvironmentLock.release(host);
    }

    private void beginRunning() {
        running = true;
        primary.setVisibility(View.GONE);
        done.setVisibility(View.GONE);
        if (anim != null && !reduceMotion()) { anim.setRepeatCount(LottieDrawable.INFINITE); anim.playAnimation(); }
    }

    private void finishResult(boolean ok, String okMsg, String failMsg) {
        running = false;
        if (anim != null) anim.pauseAnimation();
        if (statusDots != null) statusDots.stop();
        title.setText(getString(ok ? R.string.k2go_br_done_title : R.string.k2go_br_failed_title));
        status.setText(ok ? okMsg : failMsg);
        status.setTextColor(ContextCompat.getColor(requireContext(), ok ? R.color.k2go_leaf : R.color.k2go_amber_text));
        status.setVisibility(View.VISIBLE);
        done.setVisibility(View.VISIBLE);
        primary.setVisibility(View.GONE);
    }

    private void setStatusAnimated(String text) {
        status.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        status.setVisibility(View.VISIBLE);
        if (statusDots != null) statusDots.start(text);
    }
    private void setStatusStatic(String text) {
        if (statusDots != null) statusDots.stop();
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void goLibraryHome() {
        startActivity(new android.content.Intent(requireContext(), LibraryActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_library));
        requireActivity().finish();
    }

    private boolean reduceMotion() {
        try {
            return android.provider.Settings.Global.getFloat(requireContext().getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) { return false; }
    }

    @Override public void onDestroyView() {
        if (statusDots != null) statusDots.stop();
        super.onDestroyView();
    }
}
