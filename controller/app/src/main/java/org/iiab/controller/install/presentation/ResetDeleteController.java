/*
 * ============================================================================
 * Name        : ResetDeleteController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reset (wipe + reset the installed rootfs) and Delete/uninstall
 *               actions carved out of DeployFragment (strangler-fig, ADFA-4440).
 *               Destructive rootfs ops; cohesive. Shared state stays on the
 *               Fragment via ResetDeleteHost; managers (aria2, proot) via host
 *               accessors. No behaviour change.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.util.Log;
import android.widget.Button;

import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import org.iiab.controller.util.Snackbars;

import org.iiab.controller.MainActivity;
import org.iiab.controller.ProgressButton;
import org.iiab.controller.R;
import org.iiab.controller.util.ProcessRunner;

import java.io.File;
import org.iiab.controller.ui.dialog.BrandDialog;

public final class ResetDeleteController {

    private static final String TAG = "IIAB-ResetDeleteController";

    private final Fragment fragment;
    private final ResetDeleteHost host;

    private MainActivity mainAct;
    private File debianRootfs;
    private Button btnAdvancedReset;
    private ProgressButton btnFastDelete;

    public ResetDeleteController(Fragment fragment, ResetDeleteHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Wire the reset + delete buttons. Call from onViewCreated. */
    public void bind(MainActivity mainAct, File debianRootfs,
                     Button btnAdvancedReset, ProgressButton btnFastDelete) {
        this.mainAct = mainAct;
        this.debianRootfs = debianRootfs;
        this.btnAdvancedReset = btnAdvancedReset;
        this.btnFastDelete = btnFastDelete;
        bindDeleteButtonLogic();
        bindResetButtonLogic();
    }

    private void bindResetButtonLogic() {
        if (btnAdvancedReset == null) return;
        btnAdvancedReset.setOnClickListener(v -> {
            InstallProgressRepository repo = InstallProgressRepository.get();

            // If a reset is already in flight, this tap cancels it (the button text
            // invites "Tap to Cancel" during the download phase).
            if (repo.isRunning() && repo.currentOp() == InstallState.Op.RESET) {
                fragment.requireContext().startService(
                        new android.content.Intent(fragment.requireContext(), InstallService.class)
                                .setAction(InstallService.ACTION_CANCEL));
                return;
            }

            if (org.iiab.controller.ServerStateRepository.get().current().alive) {
                Snackbars.make(v, R.string.install_msg_server_running_lock).show();
                return;
            }
            // isSystemBusy() covers an install (or any other long op) in flight.
            if (host.isSystemBusy()) {
                Snackbars.make(v, host.getSystemBusyMessage()).show();
                return;
            }
            // NORMAL STATE: RESET START -> hand the pipeline to InstallService.
            new BrandDialog(fragment.requireContext())
                    .setTitle(R.string.install_dialog_reset_title)
                    .setMessage(R.string.install_dialog_reset_msg)
                    .setDestructive(R.string.install_dialog_reset_confirm, () -> {
                        mainAct.invalidateModuleStateTrust();
                        android.content.Context ctx = fragment.requireContext();
                        android.content.Intent i = new android.content.Intent(ctx, InstallService.class);
                        i.setAction(InstallService.ACTION_START);
                        i.putExtra(InstallService.EXTRA_MODE, InstallService.MODE_RESET);
                        i.putExtra(InstallService.EXTRA_ARCH, host.getTermuxArch());
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            ctx.startForegroundService(i);
                        } else {
                            ctx.startService(i);
                        }
                    })
                    .setNegative(R.string.install_dialog_reset_cancel, null)
                    .show();
        });
    }

    /**
     * ADFA-5070: the legacy "fast delete" is switched off, not deleted.
     *
     * <p>It is the only destructive route that removes the rootfs without taking the
     * shared {@code EnvironmentLock} or planting {@code InstallGuard}'s marker. It
     * has its own ad-hoc gating — server alive, host busy, a local protection flag —
     * but because it holds neither shared lock it is invisible to every other flow,
     * and a delete killed half-way leaves no marker, so the boot check cannot detect
     * the wreckage it leaves.
     *
     * <p>It is also legacy surface: {@code ResetDeleteController} is instantiated
     * only by {@code DeployFragment}, the pre-redesign god class. The current flow
     * reaches the same outcome through the wizard's reinstall, which is guarded.
     *
     * <p>One flag rather than a deletion, so turning it back on for debugging is a
     * one-line change and the code stays readable while the screen is retired.
     */
    private static final boolean LEGACY_FAST_DELETE_ENABLED = false;

    private void bindDeleteButtonLogic() {
        if (!LEGACY_FAST_DELETE_ENABLED) {
            // Hidden rather than disabled: updateDynamicButtons re-enables buttons and
            // resets their alpha, but never touches visibility, so this survives.
            btnFastDelete.setVisibility(android.view.View.GONE);
            return;
        }
        btnFastDelete.setOnClickListener(v -> {
            if (org.iiab.controller.ServerStateRepository.get().current().alive) {
                Snackbars.make(v, R.string.install_msg_server_running_lock).show();
                return;
            }
            if (host.isSystemBusy()) {
                Snackbars.make(v, host.getSystemBusyMessage()).show();
                return;
            }

            new BrandDialog(fragment.requireContext())
                    .setTitle(R.string.install_dialog_delete_title)
                    .setMessage(R.string.install_dialog_delete_msg)
                    .setDestructive(R.string.install_btn_delete_confirm, () -> {
                        host.setDeleting(true);
                        mainAct.runOnUiThread(host::updateDynamicButtons);

                        mainAct.invalidateModuleStateTrust();
                        btnFastDelete.setEnabled(false);
                        btnFastDelete.startProgress();
                        Snackbars.make(fragment.getView(), R.string.install_status_deleting).show();
                        new Thread(() -> {
                            host.enableSystemProtection();
                            try {
                                // ADFA-5070: unreachable while LEGACY_FAST_DELETE_ENABLED is
                                // false, and wired anyway — flipping the flag back on must
                                // not also bring back the stale state it used to leave.
                                // mainAct rather than fragment.requireContext(): this runs on a
                                // worker thread and the fragment may have detached by now.
                                org.iiab.controller.system.data.ContentStateInvalidator
                                        .systemReplaced(mainAct,
                                                org.iiab.controller.system.domain
                                                        .SystemReplacement.Cause.DELETE);
                                ProcessRunner.Result wipeResult = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                if (!wipeResult.isSuccess()) {
                                    Log.w(TAG, "rm -rf rootfs (delete) failed (exit " + wipeResult.exitCode + "): " + wipeResult.output);
                                }
                            } catch (Exception e) {
                                mainAct.runOnUiThread(() -> Snackbars.make(fragment.getView(), fragment.getString(R.string.install_error_delete, e.getMessage())).show());
                            } finally {
                                host.setDeleting(false);
                                mainAct.runOnUiThread(() -> { btnFastDelete.stopProgress(); host.updateDynamicButtons(); });
                                host.disableSystemProtection();
                            }
                        }).start();
                    })
                    .setNegative(R.string.cancel, null)
                    .show();
        });
    }
}
