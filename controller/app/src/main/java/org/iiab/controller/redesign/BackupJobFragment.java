/*
 * ============================================================================
 * Name        : BackupJobFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4952 / 4957 / 4961. Backup OR Restore operation screen (mode arg), styled as a
 *               compact copy of the module-install template (fragment_k2go_backup_job.xml): title +
 *               subtitle, the shared working Lottie with the app logo, a fixed 2-line status line, the
 *               "this takes time" card, and a Finish button. There is no Start button — the intro card
 *               is the trigger, so this screen auto-opens the file picker on a fresh entry.
 *
 *               Flow: pick (SAF) -> DeepOpService owns the kill-sensitive work off the UI -> this screen
 *               observes DeepOpProgressRepository (re-binds after recreation / notification return). At
 *               the terminal state it shows the result and STAYS (like the module card); Finish returns
 *               to the bifurcation (BackupRestoreFragment), which then runs the index-style "returning
 *               to Home" countdown. Back while running is consumed with a styled snackbar (hard gate).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.airbnb.lottie.LottieAnimationView;

import org.iiab.controller.R;
import org.iiab.controller.deepop.DeepOpProgressRepository;
import org.iiab.controller.deepop.DeepOpService;
import org.iiab.controller.deepop.DeepOpState;
import org.iiab.controller.env.EnvironmentLock;
import org.iiab.controller.ui.dialog.BrandDialog;
import org.iiab.controller.util.AppExecutors;
import org.iiab.controller.util.Snackbars;

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
    private TextView title, sub, status, finish;
    private org.iiab.controller.util.EllipsisAnimator statusDots;
    private boolean running = false;
    private long lastSeq = -1L;
    private androidx.activity.OnBackPressedCallback backGate;

    private final ActivityResultLauncher<String> createDoc =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
                if (uri != null) startBackup(uri); else popToIntro(false);
            });
    private final ActivityResultLauncher<String[]> openDoc =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) prepareRestore(uri); else popToIntro(false);
            });

    private boolean isRestore() { return MODE_RESTORE.equals(mode); }
    private EnvironmentLock.Owner myOwner() { return isRestore() ? EnvironmentLock.Owner.RESTORE : EnvironmentLock.Owner.BACKUP; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        mode = getArguments() != null ? getArguments().getString(ARG_MODE, MODE_BACKUP) : MODE_BACKUP;
        View v = inflater.inflate(R.layout.fragment_k2go_backup_job, container, false);
        anim = v.findViewById(R.id.k2go_bj_anim);
        title = v.findViewById(R.id.k2go_bj_title);
        sub = v.findViewById(R.id.k2go_bj_sub);
        status = v.findViewById(R.id.k2go_bj_status);
        finish = v.findViewById(R.id.k2go_bj_finish);
        statusDots = new org.iiab.controller.util.EllipsisAnimator(status);

        title.setText(getString(isRestore() ? R.string.k2go_br_restore_title : R.string.k2go_br_backup_title));
        sub.setText(getString(isRestore() ? R.string.k2go_br_restore_sub : R.string.k2go_br_backup_sub));
        finish.setOnClickListener(x -> popToIntro(true));

        // Hard gate: while the op runs, back is consumed with a styled snackbar (module-index behavior).
        backGate = new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                // ADFA-4961 (B): the op keeps running in DeepOpService, so leaving doesn't interrupt it.
                // Show a soft, mode-specific hint (anchored to the activity so it survives the pop) and
                // return to the bifurcation — no hard block. The notification brings the user back.
                Snackbars.make(requireActivity().findViewById(android.R.id.content),
                        isRestore() ? R.string.k2go_br_leave_restore : R.string.k2go_br_leave_backup).show();
                popToIntro(false);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backGate);

        DeepOpState cur = DeepOpProgressRepository.get().current();
        lastSeq = cur.seq;
        DeepOpProgressRepository.get().state().observe(getViewLifecycleOwner(), this::onDeepOpState);

        if (cur.owner == myOwner() && cur.isRunning()) {
            beginRunning();                                   // deep-link / recreation into a live op
            setStatusAnimated(cur.step);
        } else if (s != null && cur.owner == myOwner() && cur.isTerminal()) {
            showTerminal(cur.phase == DeepOpState.Phase.SUCCESS, cur.message);   // recreated at the result
        } else if (s == null) {
            v.post(this::launchPicker);                       // fresh entry: the intro card is the Start
        }
        return v;
    }

    private void launchPicker() {
        if (EnvironmentLock.isHeld(requireContext())) {   // another deep-env op owns the environment
            Snackbars.make(requireView(), R.string.k2go_install_busy).show();
            popToIntro(false);
            return;
        }
        if (isRestore()) openDoc.launch(new String[]{"application/gzip", "application/x-gzip", "*/*"});
        else createDoc.launch(org.iiab.controller.backup.domain.BackupEngine.suggestedFileName(requireContext()));
    }

    // ---- BACKUP ----
    private void startBackup(Uri uri) {
        if (!isAdded()) return;
        beginRunning();
        setStatusAnimated(getString(R.string.k2go_br_status_stopping));
        DeepOpService.startBackup(requireContext(), uri);
    }

    // ---- RESTORE: copy + validate + destructive confirm HERE (pre-extract), then the service ----
    private void prepareRestore(Uri uri) {
        if (!isAdded()) return;
        beginRunning();
        setStatusAnimated(getString(R.string.k2go_br_status_checking));
        AppExecutors.get().io().execute(() -> {
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
            main.post(() -> onValidated(temp, vr));
        });
    }

    private void onValidated(File temp, org.iiab.controller.deploy.data.RootfsArchiveValidator.Result vr) {
        if (!isAdded()) return;
        boolean ok = vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK
                || vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK_NO_MANIFEST
                || vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.OK_NO_CHECKSUM;
        if (!ok) {
            if (temp.exists()) temp.delete();
            int msg = vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.WRONG_ARCH ? R.string.install_error_wrong_arch
                    : vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.CORRUPT ? R.string.install_error_corrupt
                    : R.string.install_error_not_rootfs;
            showTerminal(false, getString(msg));
            return;
        }
        new BrandDialog(requireContext())
                .setTitle(getString(R.string.k2go_br_restore_title))
                .setMessage(getString(R.string.k2go_br_restore_warn))
                .setPositive(R.string.k2go_br_restore_confirm, BrandDialog.Role.DESTRUCTIVE, () -> {
                    setStatusAnimated(getString(R.string.k2go_br_status_stopping));
                    DeepOpService.startRestore(requireContext(), temp.getAbsolutePath());
                })
                .setNegative(R.string.cancel, () -> { if (temp.exists()) temp.delete(); popToIntro(false); })
                .show();
    }

    // ---- observe the app-scoped op state (DeepOpService is the writer) ----
    private void onDeepOpState(DeepOpState st) {
        if (st == null || !isAdded() || st.owner != myOwner()) return;
        if (st.isRunning()) {
            if (!running) beginRunning();
            setStatusAnimated(st.step);
        } else if (st.isTerminal() && st.seq > lastSeq) {
            lastSeq = st.seq;
            showTerminal(st.phase == DeepOpState.Phase.SUCCESS, st.message);
        }
    }

    private void beginRunning() {
        running = true;
        if (backGate != null) backGate.setEnabled(true);
        finish.setVisibility(View.GONE);
        if (anim != null) anim.playAnimation();
    }

    private void showTerminal(boolean ok, String message) {
        running = false;
        if (backGate != null) backGate.setEnabled(false);   // done → back / Finish returns to the bifurcation
        if (statusDots != null) statusDots.stop();
        if (anim != null) anim.pauseAnimation();
        title.setText(getString(ok ? R.string.k2go_br_done_title : R.string.k2go_br_failed_title));
        status.setText(message);
        status.setTextColor(ContextCompat.getColor(requireContext(), ok ? R.color.k2go_leaf : R.color.k2go_amber_text));
        finish.setVisibility(View.VISIBLE);
    }

    private void setStatusAnimated(String text) {
        status.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        if (statusDots != null) statusDots.start(text);
    }

    /** Return to the bifurcation (BackupRestoreFragment). When {@code fromFinish}, arm the intro's
     *  index-style "returning to Home" countdown. */
    private void popToIntro(boolean fromFinish) {
        if (!isAdded()) return;
        if (fromFinish) BackupRestoreFragment.armReturnCountdown();
        androidx.fragment.app.FragmentManager fm = requireActivity().getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
        } else {
            // Deep-linked straight to the job screen (no bifurcation underneath, e.g. the notification
            // reopen) — show the bifurcation so Finish always lands there (which then runs the countdown
            // to Home), never jumping straight Home.
            fm.beginTransaction().replace(R.id.k2go_setup_host, new BackupRestoreFragment()).commit();
        }
    }

    @Override public void onDestroyView() {
        if (statusDots != null) statusDots.stop();
        super.onDestroyView();
    }
}
