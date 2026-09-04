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
package org.appdevforall.k2go.redesign;

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

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.deepop.DeepOpProgressRepository;
import org.appdevforall.k2go.deepop.DeepOpService;
import org.appdevforall.k2go.deepop.DeepOpState;
import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.ui.dialog.BrandDialog;
import org.appdevforall.k2go.util.Snackbars;

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
    /** K2GO-384: per-pass ETA caption next to the percent; blank when the pass can't estimate yet. */
    private TextView progressEta;
    /** K2GO-384: on-screen Cancel, shown only while a RESTORE is running and still before the extract. */
    private View cancel;
    /** K2GO-384: the current pass's cancel semantics (single source = DeepOpState.cancelKind). */
    private DeepOpState.CancelKind lastCancelKind = DeepOpState.CancelKind.NONE;
    private org.appdevforall.k2go.util.EllipsisAnimator statusDots;
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
        progressEta = v.findViewById(R.id.k2go_bj_progress_eta);
        cancel = v.findViewById(R.id.k2go_bj_cancel);
        finish = v.findViewById(R.id.k2go_bj_finish);
        waitCard = v.findViewById(R.id.k2go_bj_wait_card);
        // ADFA-4947 fixed-width mode: this status line is centred, so variable-width dots slide the
        // whole label left and right as they grow. Same choice LibraryActivity's boot lines make.
        statusDots = new org.appdevforall.k2go.util.EllipsisAnimator(status, true);

        title.setText(getString(isRestore() ? R.string.k2go_br_restore_title : R.string.k2go_br_backup_title));
        sub.setText(getString(isRestore() ? R.string.k2go_br_restore_sub : R.string.k2go_br_backup_sub));
        finish.setOnClickListener(x -> popToIntro(true));
        cancel.setOnClickListener(x -> onCancelTapped());

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
            // K2GO-384: a CANCELLED terminal returns to the bifurcation even when the fragment is recreated
            // exactly at it (config change) -- the decision lives on the phase, not a fragment flag.
            if (cur.phase == DeepOpState.Phase.CANCELLED) v.post(() -> popToIntro(false));
            else showTerminal(cur.phase == DeepOpState.Phase.SUCCESS, cur.message);   // recreated at the result
        } else if (s == null) {
            v.post(this::launchPicker);                       // fresh entry: the intro card is the Start
        }
        return v;
    }

    private void launchPicker() {
        if (EnvironmentLock.isHeld(requireContext())) {   // another deep-env op owns the environment
            Snackbars.make(requireView(), org.appdevforall.k2go.util.BusyMessage.resFor(requireContext())).show();
            popToIntro(false);
            return;
        }
        if (isRestore()) openDoc.launch(new String[]{"application/gzip", "application/x-gzip", "*/*"});
        else createDoc.launch(org.appdevforall.k2go.backup.domain.BackupEngine.suggestedFileName(requireContext()));
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
    private void showProgress(int percent, long etaSeconds) {
        if (progressRow == null) return;
        final boolean measurable = percent >= 0;
        progressRow.setVisibility(measurable ? View.VISIBLE : View.GONE);
        if (measurable) {
            progress.setProgressCompat(percent, true);
            progressPct.setText(percent + "%");   // matches the module-install and maps screens
        }
        // K2GO-384: the per-pass ETA ("~2 min"), reusing the install screens' EtaText so the wording
        // lives in one place. Blank (unknown, or not measurable yet) leaves the slot empty rather than
        // showing a stale estimate — the bar and % already carry the progress.
        if (progressEta != null) {
            progressEta.setText(measurable
                    ? org.appdevforall.k2go.install.presentation.EtaText.of(requireContext(), etaSeconds)
                    : "");
        }
    }

    // ---- observe the app-scoped op state (DeepOpService is the writer) ----
    private void onDeepOpState(DeepOpState st) {
        if (st == null || !isAdded() || st.owner != myOwner()) return;
        if (st.isRunning()) {
            if (!running) beginRunning();
            setStatusAnimated(st.step);
            showProgress(st.percent, st.etaSeconds);
            lastCancelKind = st.cancelKind;   // K2GO-384: track the current pass's cancel semantics
            updateCancelVisibility();
        } else if (st.isTerminal() && st.seq > lastSeq) {
            lastSeq = st.seq;
            showProgress(-1, -1L);   // K2GO-372: a finished op has no bar to keep filling
            // K2GO-384: a user cancel in the safe zone is its own terminal (CANCELLED) -- return to the
            // bifurcation, not a "something went wrong" screen. The service owns that distinction now (the
            // phase), so it holds across a config change. FAILED (a real error, or the acknowledged
            // destructive cancel's "damaged" message) and SUCCESS both show a terminal.
            if (st.phase == DeepOpState.Phase.CANCELLED) { popToIntro(false); return; }
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
        updateCancelVisibility();
    }

    private void showTerminal(boolean ok, String message) {
        running = false;
        if (cancel != null) cancel.setVisibility(View.GONE);   // K2GO-384: no cancel once the op has ended
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

    /**
     * K2GO-384: on a cancellable pass (copy / stopping / verify) Cancel HOLDS the run -- the service pauses
     * the copy loop or blocks the verify->extract boundary, so nothing advances into the destructive extract
     * while the user decides -- and shows a light M3 dialog: Keep restoring (continue) / Cancel restore
     * (abort, system unchanged). During the DESTRUCTIVE extract, Cancel instead shows the acknowledged
     * force-cancel dialog (red + checkbox), which leaves the system damaged for recovery.
     */
    private void onCancelTapped() {
        if (!isAdded()) return;
        if (lastCancelKind == DeepOpState.CancelKind.DESTRUCTIVE) { showDestructiveCancelDialog(); return; }
        if (lastCancelKind != DeepOpState.CancelKind.CANCELLABLE) return;
        sendToService(DeepOpService.ACTION_CANCEL);   // hold the run while the user decides (no race)
        // K2GO-384: non-cancelable -- the run is HELD (copy paused / verify blocked at the boundary) the moment
        // this shows, so the user MUST resolve it. A scrim/Back dismiss would leave pauseRequested set and hang
        // the restore forever; forcing a Keep/Cancel choice closes that lifecycle gap.
        new BrandDialog(requireContext())
                .setCancelable(false)
                .setTitle(getString(R.string.k2go_br_cancel_title))
                .setMessage(getString(R.string.k2go_br_cancel_body))
                .setPositive(R.string.k2go_br_cancel_confirm, () -> {
                    if (cancel != null) cancel.setEnabled(false);
                    sendToService(DeepOpService.ACTION_CANCEL_CONFIRM);   // abort -> CANCELLED terminal -> bifurcation
                })
                .setNegative(R.string.k2go_br_cancel_keep, () -> sendToService(DeepOpService.ACTION_RESUME))
                .show();
    }

    private void sendToService(String action) {
        if (!isAdded()) return;
        requireContext().startService(
                new android.content.Intent(requireContext(), DeepOpService.class).setAction(action));
    }

    /**
     * K2GO-384: cancelling DURING the extract is destructive -- the rootfs is being overwritten. A strong
     * M3 dialog: RED confirm (colorError) gated by an acknowledgement checkbox. Confirming force-cancels the
     * extract (kills tar mid-write); the system is left torn and reinstalls itself on next boot (InstallGuard).
     * Unlike the trivial cancel, this keeps a terminal so the user is told the system will be reinstalled.
     */
    private void showDestructiveCancelDialog() {
        if (!isAdded()) return;
        final float density = getResources().getDisplayMetrics().density;
        // ADFA-5339 pattern: a MaterialCheckBox as a dialog's custom view sits flush-left; a holder padded by
        // dialogPreferredPadding (the title/message inset) with the checkbox's own left padding at 0 lines it
        // up with the text above.
        final com.google.android.material.checkbox.MaterialCheckBox box =
                new com.google.android.material.checkbox.MaterialCheckBox(requireContext());
        box.setText(R.string.k2go_br_cancel_extract_ack);
        box.setCompoundDrawablePadding(Math.round(8 * density));
        final int pad = dialogContentPadding();
        final android.widget.FrameLayout holder = new android.widget.FrameLayout(requireContext());
        holder.setPadding(pad, Math.round(8 * density), pad, 0);
        holder.addView(box);
        final androidx.appcompat.app.AlertDialog d =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.k2go_br_cancel_title)
                        .setMessage(R.string.k2go_br_cancel_extract_body)
                        .setView(holder)
                        .setPositiveButton(R.string.k2go_br_cancel_confirm, null)   // click set below to gate dismiss
                        .setNegativeButton(R.string.k2go_br_cancel_keep, null)
                        .show();
        final android.widget.Button confirm = d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        confirm.setTextColor(ContextCompat.getColor(requireContext(), R.color.btn_danger));   // same red as BrandDialog DESTRUCTIVE
        // K2GO-384: keep the button ENABLED and validate on click -- an inert disabled button gives no
        // feedback; a snackbar tells the user why nothing happened until they acknowledge.
        confirm.setOnClickListener(v -> {
            if (!box.isChecked()) {
                Snackbars.make(requireActivity().findViewById(android.R.id.content),
                        R.string.k2go_br_cancel_extract_need_ack).show();
                return;
            }
            // K2GO-384: the destructive kill ends as FAILED with the "damaged" message (non-empty), so the
            // terminal shows -- the user sees "the next launch will start recovery" (no CANCELLED short-circuit).
            if (cancel != null) cancel.setEnabled(false);
            sendToService(DeepOpService.ACTION_FORCE_CANCEL);
            d.dismiss();
        });
    }

    /** The dialog's horizontal content inset (title/message use it); a custom view must match it to line up
     *  (ADFA-5339). */
    private int dialogContentPadding() {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (requireContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, tv, true)) {
            return android.util.TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        }
        return Math.round(24 * getResources().getDisplayMetrics().density);   // Material default
    }

    /** K2GO-384: Cancel is shown while the current pass is cancellable (PAUSABLE copy or ABORTABLE
     *  verify/stopping). It hides at the destructive extract and when there is nothing to cancel. */
    private void updateCancelVisibility() {
        if (cancel == null || !isAdded()) return;
        boolean show = running && (lastCancelKind == DeepOpState.CancelKind.CANCELLABLE
                || lastCancelKind == DeepOpState.CancelKind.DESTRUCTIVE);
        cancel.setVisibility(show ? View.VISIBLE : View.GONE);
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
