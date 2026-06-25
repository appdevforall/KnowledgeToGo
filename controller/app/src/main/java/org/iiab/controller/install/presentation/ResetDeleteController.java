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

import org.iiab.controller.Aria2Manager;
import org.iiab.controller.MainActivity;
import org.iiab.controller.PRootEngine;
import org.iiab.controller.ProgressButton;
import org.iiab.controller.R;
import org.iiab.controller.util.ProcessRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

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

            if (mainAct.isServerAlive) {
                Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (host.isSystemBusy()) {
                Snackbar.make(v, host.getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                return;
            }
            // NORMAL STATE: RESET START
            new android.app.AlertDialog.Builder(fragment.requireContext())
                    .setTitle(R.string.install_dialog_reset_title)
                    .setMessage(R.string.install_dialog_reset_msg)
                    .setPositiveButton(R.string.install_dialog_reset_confirm, (dialog, which) -> {
                        // IMMEDIATE LOCK AGAINST DOUBLE PRESS
                        if (host.isDownloadingRootfs()) return;
                        host.setDownloadingRootfs(true);

                        mainAct.invalidateModuleStateTrust();
                        final String originalText = fragment.getString(R.string.install_btn_reset);

                        // We enable the button but change the text to serve as "Cancel"
                        btnAdvancedReset.setEnabled(true);

                        new Thread(() -> {
                            host.enableSystemProtection();
                            try {
                                mainAct.runOnUiThread(() -> {
                                    btnAdvancedReset.setText(fragment.getString(R.string.install_status_wiping_old));
                                    Snackbar.make(fragment.getView(), R.string.install_status_starting_vanilla, Snackbar.LENGTH_SHORT).show();
                                });

                                // 1. WIPE
                                ProcessRunner.Result wipeResult = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                if (!wipeResult.isSuccess()) {
                                    Log.w(TAG, "rm -rf rootfs (vanilla reset) failed (exit " + wipeResult.exitCode + "): " + wipeResult.output);
                                }
                                debianRootfs.mkdirs();

                                // 2. DOWNLOAD
                                mainAct.runOnUiThread(() -> btnAdvancedReset.setText(fragment.getString(R.string.install_status_downloading_debian)));
                                if (host.aria2Manager() == null) host.setAria2Manager(new Aria2Manager());

                                String arch = host.getTermuxArch();
                                String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "arm" : "aarch64";
                                String tarball = "debian-trixie-" + archSuffix + "-pd-v4.29.0.tar.xz";
                                String url = "https://iiab.switnet.org/android/rootfs/proot-distro-v4.29.0/" + tarball;

                                host.aria2Manager().startDownload(fragment.requireContext(), url, new Aria2Manager.DownloadListener() {
                                    @Override
                                    public void onProgress(int percentage, String speed, String eta) {
                                        if (fragment.isAdded() && fragment.getActivity() != null) {
                                            fragment.getActivity().runOnUiThread(() -> {
                                                // We keep it visible that you can cancel
                                                btnAdvancedReset.setText(fragment.getString(R.string.install_status_debian_download, percentage, speed) + " (Tap to Cancel)");
                                            });
                                        }
                                    }

                                    @Override
                                    public void onComplete(String downloadPath) {
                                        new Thread(() -> {
                                            try {
                                                // 3. EXTRACT
                                                host.setDownloadingRootfs(false);
                                                mainAct.runOnUiThread(() -> {
                                                    btnAdvancedReset.setText(fragment.getString(R.string.install_status_extracting_base));
                                                    btnAdvancedReset.setEnabled(false); // <--- SAFE DOOR CLOSURE
                                                });

                                                File downloadedArchive = new File(downloadPath, tarball);
                                                File staticTar = new File(fragment.requireContext().getApplicationInfo().nativeLibraryDir, "libtar.so");
                                                File staticXz = new File(fragment.requireContext().getApplicationInfo().nativeLibraryDir, "libxz.so");
                                                String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";
                                                String xzBin = staticXz.exists() ? staticXz.getAbsolutePath() : "xz";

                                                // Pipe xz directly into tar to bypass Android's limited PATH
                                                String extractCmd = xzBin + " -d -c " + downloadedArchive.getAbsolutePath() + " | " + tarBin + " --exclude='*/dev/*' --strip-components=1 -xf - -C " + debianRootfs.getAbsolutePath();

                                                Process pExt = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", extractCmd});
                                                BufferedReader errReader = new BufferedReader(new java.io.InputStreamReader(pExt.getErrorStream()));
                                                StringBuilder errMsg = new StringBuilder();
                                                String errLine;
                                                while ((errLine = errReader.readLine()) != null) {
                                                    errMsg.append(errLine).append("\n");
                                                    android.util.Log.e(TAG, "[TAR Extractor] " + errLine);
                                                }

                                                int exitCode = pExt.waitFor();
                                                if (exitCode != 0) {
                                                    throw new Exception("Extraction failed (Code " + exitCode + "):\n" + errMsg.toString());
                                                }

                                                downloadedArchive.delete();

                                                // 4. BOOTSTRAP IIAB
                                                mainAct.runOnUiThread(() -> btnAdvancedReset.setText(fragment.getString(R.string.install_status_bootstrapping)));

                                                // DNS is written at the chokepoint (PRootEngine) before the
                                                // bootstrap proot run below; no inline write needed here.

                                                if (host.prootEngine() == null)
                                                    host.setPRootEngine(new PRootEngine());
                                                String bootstrapCmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                                                        "export DEBIAN_FRONTEND=noninteractive && " +
                                                        "apt-get update && apt-get install -y curl ca-certificates nano sudo && " +
                                                        "curl -fsSL https://raw.githubusercontent.com/appdevforall/KnowledgeToGo/main/iiab-android -o /usr/local/sbin/iiab-android && " +
                                                        "chmod +x /usr/local/sbin/iiab-android && " +
                                                        "apt-get clean && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* /tmp/* /root/.cache";

                                                host.prootEngine().executeInContainer(fragment.requireContext(), debianRootfs.getAbsolutePath(), "/bin/bash -c '" + bootstrapCmd + "'", new PRootEngine.OutputListener() {
                                                    @Override
                                                    public void onOutputLine(String line) {
                                                        mainAct.runOnUiThread(() -> mainAct.addToLog("[Bootstrap] " + line));
                                                    }

                                                    @Override
                                                    public void onProcessExit(int exitCode) {
                                                        mainAct.runOnUiThread(() -> {
                                                            host.disableSystemProtection();
                                                            btnAdvancedReset.setText(originalText);
                                                            btnAdvancedReset.setEnabled(true);
                                                            host.updateDynamicButtons();
                                                            Snackbar.make(fragment.getView(), R.string.install_success_vanilla, Snackbar.LENGTH_LONG).show();
                                                        });
                                                    }

                                                    @Override
                                                    public void onError(String error) {
                                                        mainAct.runOnUiThread(() -> {
                                                            host.disableSystemProtection();
                                                            btnAdvancedReset.setText(originalText);
                                                            btnAdvancedReset.setEnabled(true);
                                                            host.updateDynamicButtons();
                                                            Snackbar.make(fragment.getView(), fragment.getString(R.string.install_error_bootstrap, error), Snackbar.LENGTH_LONG).show();
                                                        });
                                                    }
                                                });

                                            } catch (Exception e) {
                                                mainAct.runOnUiThread(() -> {
                                                    host.setDownloadingRootfs(false);
                                                    host.disableSystemProtection();
                                                    btnAdvancedReset.setText(originalText);
                                                    btnAdvancedReset.setEnabled(true);
                                                    host.updateDynamicButtons();
                                                    Snackbar.make(fragment.getView(), fragment.getString(R.string.install_error_extract_bootstrap, e.getMessage()), Snackbar.LENGTH_LONG).show();
                                                });
                                            }
                                        }).start();
                                    }

                                    @Override
                                    public void onError(String error) {
                                        mainAct.runOnUiThread(() -> {
                                            host.setDownloadingRootfs(false);
                                            host.disableSystemProtection();
                                            btnAdvancedReset.setText(originalText);
                                            btnAdvancedReset.setEnabled(true);
                                            host.updateDynamicButtons();
                                            Snackbar.make(fragment.getView(), fragment.getString(R.string.install_error_download, error), Snackbar.LENGTH_LONG).show();
                                        });
                                    }
                                });

                            } catch (Exception e) {
                                mainAct.runOnUiThread(() -> {
                                    host.setDownloadingRootfs(false);
                                    host.disableSystemProtection();
                                    btnAdvancedReset.setText(originalText);
                                    btnAdvancedReset.setEnabled(true);
                                    host.updateDynamicButtons();
                                    Snackbar.make(fragment.getView(), fragment.getString(R.string.install_error_reset, e.getMessage()), Snackbar.LENGTH_LONG).show();
                                });
                            }
                        }).start();
                    })
                    .setNegativeButton(R.string.install_dialog_reset_cancel, null)
                    .show();
        });
    }

    private void bindDeleteButtonLogic() {
        btnFastDelete.setOnClickListener(v -> {
            if (mainAct.isServerAlive) {
                Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (host.isSystemBusy()) {
                Snackbar.make(v, host.getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                return;
            }

            new android.app.AlertDialog.Builder(fragment.requireContext())
                    .setTitle(R.string.install_dialog_delete_title)
                    .setMessage(R.string.install_dialog_delete_msg)
                    .setPositiveButton(R.string.install_btn_delete_confirm, (dialog, which) -> {
                        host.setDeleting(true);
                        mainAct.runOnUiThread(host::updateDynamicButtons);

                        mainAct.invalidateModuleStateTrust();
                        btnFastDelete.setEnabled(false);
                        btnFastDelete.startProgress();
                        Snackbar.make(fragment.getView(), R.string.install_status_deleting, Snackbar.LENGTH_SHORT).show();
                        new Thread(() -> {
                            host.enableSystemProtection();
                            try {
                                ProcessRunner.Result wipeResult = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                if (!wipeResult.isSuccess()) {
                                    Log.w(TAG, "rm -rf rootfs (delete) failed (exit " + wipeResult.exitCode + "): " + wipeResult.output);
                                }
                            } catch (Exception e) {
                                mainAct.runOnUiThread(() -> Snackbar.make(fragment.getView(), fragment.getString(R.string.install_error_delete, e.getMessage()), Snackbar.LENGTH_LONG).show());
                            } finally {
                                host.setDeleting(false);
                                mainAct.runOnUiThread(() -> { btnFastDelete.stopProgress(); host.updateDynamicButtons(); });
                                host.disableSystemProtection();
                            }
                        }).start();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }
}
