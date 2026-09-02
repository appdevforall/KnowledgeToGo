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
import org.iiab.controller.util.Snackbars;

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
    private View waitCard;   // "this can take a while" — only true while it is still running
    /** K2GO-372: determinate progress, hidden until the op reports a percent it can stand behind. */
    private View progressRow;
    private com.google.android.material.progressindicator.LinearProgressIndicator progress;
    private TextView progressPct;
    private org.iiab.controller.util.EllipsisAnimator statusDots;
    private boolean running = false;
    private long lastSeq = -1L;
    private boolean leaveWarned = false;   // ADFA-4971: first Back reassures, later Backs background the app
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
        // ADFA-4961: mode-specific working loop (box + streaming dots + glow); the app logo is composited
        // on top by the layout scaffold, same as the module-install template.
        anim.setAnimation(isRestore() ? R.raw.k2go_restore_loop : R.raw.k2go_backup_loop);
        anim.setRepeatCount(com.airbnb.lottie.LottieDrawable.INFINITE);
        anim.playAnimation();
        title = v.findViewById(R.id.k2go_bj_title);
        sub = v.findViewById(R.id.k2go_bj_sub);
        status = v.findViewById(R.id.k2go_bj_status);
        progressRow = v.findViewById(R.id.k2go_bj_progress_row);
        progress = v.findViewById(R.id.k2go_bj_progress);
        progressPct = v.findViewById(R.id.k2go_bj_progress_pct);
        finish = v.findViewById(R.id.k2go_bj_finish);
        waitCard = v.findViewById(R.id.k2go_bj_wait_card);
        // ADFA-4947 fixed-width mode: this status line is centred, so variable-width dots slide the
        // whole label left and right as they grow. Same choice LibraryActivity's boot lines make.
        statusDots = new org.iiab.controller.util.EllipsisAnimator(status, true);

        title.setText(getString(isRestore() ? R.string.k2go_br_restore_title : R.string.k2go_br_backup_title));
        sub.setText(getString(isRestore() ? R.string.k2go_br_restore_sub : R.string.k2go_br_backup_sub));
        finish.setOnClickListener(x -> popToIntro(true));

        // Hard gate: while the op runs, back is consumed with a styled snackbar (module-index behavior).
        backGate = new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                // ADFA-4971: confine like the module index (SetupProgressActivity.onBackPressed) — but
                // only once the SERVICE owns the op (there is a notification and the work survives
                // backgrounding). Then the op runs with the server stopped, and walking Back to a
                // server-down Home is what triggered the false "reinstall" recovery: first Back
                // reassures with the soft hint, every Back after sends the app to the background — never
                // in-app nav to Home. During the pre-service copy+validate phase (restore) there's no
                // notification yet and nothing is destructive, so a Back there just cancels to the
                // bifurcation.
                DeepOpState cur = DeepOpProgressRepository.get().current();
                if (cur.owner == myOwner() && cur.isRunning()) {
                    if (!leaveWarned) {
                        leaveWarned = true;
                        Snackbars.make(requireActivity().findViewById(android.R.id.content),
                                isRestore() ? R.string.k2go_br_leave_restore : R.string.k2go_br_leave_backup).show();
                    } else {
                        requireActivity().moveTaskToBack(true);
                    }
                } else {
                    popToIntro(false);
                }
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
            Snackbars.make(requireView(), org.iiab.controller.util.BusyMessage.resFor(requireContext())).show();
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

    // ---- RESTORE: destructive confirm FIRST, then the service does every slow read ----

    /**
     * K2GO-372: ask before reading, not after.
     *
     * <p>This used to copy the picked file into the cache and fully validate it — two complete reads of
     * a multi-gigabyte archive, three silent minutes — and only then show the destructive confirm. That
     * forced the user to stay and watch a motionless screen for the one question the archive's content
     * does not answer: do you want to replace your system? The confirm needs the file's name, not its
     * bytes, so it is asked here and everything slow happens afterwards, in {@link DeepOpService}, with
     * progress and a notification to come back to.
     *
     * <p>Confirming is still not the point of no return: the extractor checks identity, then the whole
     * listing, and fails closed before it writes anything ({@code TarExtractor}). A rejected archive
     * leaves the current system untouched, which is what made the pre-flight pass safe to delete rather
     * than merely move — it was a second place answering a question the extractor already answers.
     */
    private void prepareRestore(Uri uri) {
        if (!isAdded()) return;
        new BrandDialog(requireContext())
                .setTitle(getString(R.string.k2go_br_restore_title))
                .setMessage(getString(R.string.k2go_br_restore_warn))
                .setPositive(R.string.k2go_br_restore_confirm, BrandDialog.Role.DESTRUCTIVE, () -> {
                    beginRunning();
                    setStatusAnimated(getString(R.string.k2go_br_status_copying));
                    DeepOpService.startRestore(requireContext(), uri);
                })
                .setNegative(R.string.cancel, () -> popToIntro(false))
                .show();
    }

    /**
     * K2GO-372: show the bar only for a percent the op actually measured.
     *
     * <p>A negative percent means the step cannot say how far along it is, and a bar frozen at zero
     * reads as a stall — worse than no bar. Those steps keep the animation above instead.
     */
    private void showProgress(int percent) {
        if (progressRow == null) return;
        final boolean measurable = percent >= 0;
        progressRow.setVisibility(measurable ? View.VISIBLE : View.GONE);
        if (measurable) {
            progress.setProgressCompat(percent, true);
            progressPct.setText(percent + "%");   // matches the module-install and maps screens
        }
    }

    // ---- observe the app-scoped op state (DeepOpService is the writer) ----
    private void onDeepOpState(DeepOpState st) {
        if (st == null || !isAdded() || st.owner != myOwner()) return;
        if (st.isRunning()) {
            if (!running) beginRunning();
            setStatusAnimated(st.step);
            showProgress(st.percent);
        } else if (st.isTerminal() && st.seq > lastSeq) {
            lastSeq = st.seq;
            showProgress(-1);   // K2GO-372: a finished op has no bar to keep filling
            showTerminal(st.phase == DeepOpState.Phase.SUCCESS, st.message);
        }
    }

    private void beginRunning() {
        running = true;
        leaveWarned = false;   // ADFA-4971: each op's first Back re-warns before backgrounding
        if (backGate != null) backGate.setEnabled(true);
        finish.setVisibility(View.GONE);
        if (waitCard != null) waitCard.setVisibility(View.VISIBLE);
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
        // The card asks the user to keep the app open because the op is still running; once it has
        // finished it is telling them to wait for something that already happened.
        if (waitCard != null) waitCard.setVisibility(View.GONE);
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
