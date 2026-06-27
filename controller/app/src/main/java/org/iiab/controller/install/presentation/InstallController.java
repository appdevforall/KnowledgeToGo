/*
 * ============================================================================
 * Name        : InstallController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Install pipeline carved out of DeployFragment (strangler-fig,
 *               ADFA-4434 PR 2): the install button, the module install queue
 *               and the per-role provisioning (download + Ansible runrole + maps
 *               + local-vars + install-state verification). It is one cohesive,
 *               cyclic call-graph, so it moves as a single controller. Shared
 *               state stays on the Fragment via InstallHost; managers (aria2,
 *               proot) are reached through the host. No behaviour change.
 *               See controller/docs/TECH_DEBT_PLAN.md.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import org.iiab.controller.Aria2Manager;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.MainActivity;
import org.iiab.controller.ModuleRegistry;
import org.iiab.controller.PRootEngine;
import org.iiab.controller.ProgressButton;
import org.iiab.controller.R;
import org.iiab.controller.TarExtractor;
import org.iiab.controller.deploy.domain.ModuleName;
import org.iiab.controller.install.domain.AnsibleRunOutcome;
import org.iiab.controller.util.LocalVarsYamlParser;
import org.iiab.controller.util.ProcessRunner;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public final class InstallController {

    private static final String TAG = "IIAB-InstallController";

    private final Fragment fragment;
    private final InstallHost host;

    private MainActivity mainAct;
    private File debianRootfs;
    private File iiabRootDir;
    private ProgressButton btnFastInstall;
    private Button btnLaunchInstall;
    private LinearLayout discrepancyWarning;
    private LinearLayout rolesContainer;
    private CheckBox chkCompanionData;

    public InstallController(Fragment fragment, InstallHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Wire the install/fast-install buttons. Call from onViewCreated. */
    public void bind(MainActivity mainAct, File debianRootfs, File iiabRootDir,
                     ProgressButton btnFastInstall, Button btnLaunchInstall,
                     LinearLayout discrepancyWarning, LinearLayout rolesContainer,
                     CheckBox chkCompanionData) {
        this.mainAct = mainAct;
        this.debianRootfs = debianRootfs;
        this.iiabRootDir = iiabRootDir;
        this.btnFastInstall = btnFastInstall;
        this.btnLaunchInstall = btnLaunchInstall;
        this.discrepancyWarning = discrepancyWarning;
        this.rolesContainer = rolesContainer;
        this.chkCompanionData = chkCompanionData;
        bindInstallButtonLogic();
    }

    private void bindInstallButtonLogic() {
        btnFastInstall.setOnClickListener(v -> {
            // 1. Main Lock: Server On
            if (mainAct.isServerAlive) {
                Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                return;
            }

            // 1b. No internet: a fresh install requires downloading the rootfs. Block it
            // up front (but still allow cancelling an in-progress download below).
            if (!host.hasInternet() && !host.isDownloadingRootfs()) {
                Snackbar.make(v, R.string.install_msg_no_connection, Snackbar.LENGTH_LONG).show();
                return;
            }

            // 2. HIGH PRIORITY: If this button is working, we allow cancel
            if (host.isDownloadingRootfs()) {
                new android.app.AlertDialog.Builder(fragment.requireContext())
                        .setTitle(fragment.getString(R.string.install_btn_cancel_title))
                        .setMessage(fragment.getString(R.string.install_btn_cancel_msg))
                        .setPositiveButton(fragment.getString(R.string.install_btn_cancel_confirm), (dialog, which) -> {
                            if (host.aria2Manager() != null) host.aria2Manager().stopDownload();
                            host.disableSystemProtection();
                            host.setDownloadingRootfs(false);
                            btnFastInstall.setText(R.string.install_btn_install);
                            btnFastInstall.setAlpha(1.0f);
                            Snackbar.make(fragment.getView(), R.string.install_msg_cancelled, Snackbar.LENGTH_SHORT).show();
                            host.updateDynamicButtons();
                        })
                        .setNegativeButton(fragment.getString(R.string.cancel), null)
                        .show();
                return;
            }

            // 3. If it is not working, but the system is busy with something else: LOCK
            if (host.isSystemBusy()) {
                Snackbar.make(v, host.getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                return;
            }

            // 4. Normal installation startup validations
            if (host.getSelectedTier() == null) {
                Snackbar.make(v, R.string.install_error_no_tier, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (!host.isStorageSafe()) {
                Snackbar.make(v, R.string.install_error_no_storage, Snackbar.LENGTH_LONG).show();
                return;
            }

            // 5. Start the installation...
            Runnable executeDownload = () -> {
                host.enableSystemProtection();
                mainAct.invalidateModuleStateTrust();
                host.setDownloadingRootfs(true);
                btnFastInstall.setAlpha(0.8f);
                btnFastInstall.startProgress();
                btnFastInstall.setTextSize(12f);

                if (host.aria2Manager() == null) host.setAria2Manager(new Aria2Manager());

                String arch = host.getTermuxArch();
                String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "armeabi-v7a" : "arm64-v8a";
                InstallationPlanner.Tier safeTier = (host.getSelectedTier() != null) ? host.getSelectedTier() : InstallationPlanner.Tier.BASIC;
                String tierString = safeTier.name().toLowerCase(java.util.Locale.US);
                String directUrl = "https://iiab.switnet.org/android/rootfs/latest_" + tierString + "_" + archSuffix + ".meta4";

                host.aria2Manager().startDownload(fragment.requireContext(), directUrl, new Aria2Manager.DownloadListener() {
                    @Override
                    public void onProgress(int percentage, String speed, String eta) {
                        // Progress flows through the observable repository so the UI
                        // re-binds after a recreation (ADFA-4474 PR1).
                        InstallProgressRepository.get().postDownloading(percentage, speed);
                    }

                    @Override
                    public void onComplete(String downloadPath) {
                        InstallProgressRepository.get().postPhase(InstallState.Phase.EXTRACTING);
                        if (!fragment.isAdded() || fragment.getActivity() == null) return;
                        mainAct.runOnUiThread(() -> btnFastInstall.setText(fragment.getString(R.string.install_status_extracting)));

                        File downloadDir = new File(downloadPath);
                        File[] archives = downloadDir.listFiles((dir, name) -> name.endsWith(".tar.xz") || name.endsWith(".tar.gz"));

                        if (archives == null || archives.length == 0) {
                            abortInstallation(fragment.getString(R.string.install_error_no_archive));
                            return;
                        }

                        File downloadedArchive = archives[0];
                        TarExtractor tarExtractor = new TarExtractor();

                        tarExtractor.startExtraction(fragment.requireContext(), downloadedArchive.getAbsolutePath(), iiabRootDir.getAbsolutePath(), new TarExtractor.ExtractionListener() {
                            @Override
                            public void onComplete(String destDir) {
                                downloadedArchive.delete();
                                File prootTmp = new File(fragment.requireContext().getCacheDir(), "proot_tmp");
                                if (!prootTmp.exists()) prootTmp.mkdirs();
                                File binDir = new File(fragment.requireContext().getFilesDir(), "usr/bin");
                                if (binDir.exists()) {
                                    try {
                                        ProcessRunner.Result chmodResult = ProcessRunner.run(new String[]{"chmod", "-R", "755", binDir.getAbsolutePath()});
                                        if (!chmodResult.isSuccess()) {
                                            Log.w(TAG, "chmod on usr/bin failed (exit " + chmodResult.exitCode + "): " + chmodResult.output);
                                        }
                                    } catch (Exception e) {
                                        Log.w(TAG, "chmod on usr/bin failed", e);
                                    }
                                }

                                // DNS is written at the single chokepoint (PRootEngine.executeInContainer),
                                // so the companion-data proot steps below get a working resolv.conf for free.

                                if (chkCompanionData.isChecked()) {
                                    editLocalVarsForMaps(debianRootfs, safeTier);
                                    android.content.SharedPreferences prefs = fragment.requireContext().getSharedPreferences(fragment.getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                                    String targetLang = (host.getOverrideKiwixLang() != null) ? host.getOverrideKiwixLang() : prefs.getString("selected_lang_minimal", "en");

                                    InstallationPlanner.calculateProjectedSize(fragment.requireContext(), safeTier, true, targetLang, host.getOverrideKiwixVariant(), new InstallationPlanner.PlanResultListener() {
                                        @Override
                                        public void onCalculated(InstallationPlanner.StorageProjection projection) {
                                            if (projection.resolvedFilename != null)
                                                downloadAndIndexKiwix(projection.resolvedFilename, debianRootfs);
                                            else runMapsAnsible(debianRootfs);
                                        }

                                        @Override
                                        public void onError(String error) {
                                            runMapsAnsible(debianRootfs);
                                        }
                                    });
                                } else {
                                    finishInstallationSuccess();
                                }
                            }

                            @Override
                            public void onError(String error) {
                                abortInstallation(fragment.getString(R.string.install_error_extraction, error));
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        abortInstallation(fragment.getString(R.string.install_error_download, error));
                    }
                });
            };

            if (debianRootfs.exists() && debianRootfs.isDirectory()) {
                new android.app.AlertDialog.Builder(fragment.requireContext())
                        .setTitle(R.string.install_btn_reinstall)
                        .setMessage(R.string.install_dialog_wipe_msg)
                        .setPositiveButton(R.string.install_btn_yes, (dialog, which) -> {
                            btnFastInstall.setText(R.string.install_status_wiping_old);
                            btnFastInstall.setEnabled(false);
                            new Thread(() -> {
                                try {
                                    ProcessRunner.Result wipeResult = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                    if (!wipeResult.isSuccess()) {
                                        Log.w(TAG, "rm -rf rootfs (reinstall) failed (exit " + wipeResult.exitCode + "): " + wipeResult.output);
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "rm -rf rootfs (reinstall) failed", e);
                                }
                                mainAct.runOnUiThread(executeDownload);
                            }).start();
                        })
                        .setNegativeButton(R.string.install_btn_no, null)
                        .show();
            } else {
                executeDownload.run();
            }
        });
    }

    private void evaluateLaunchButton() {
        if (host.isBatchInstalling()) return;

        boolean hasSelections = false;
        host.installationQueue().clear();

        for (CheckBox cb : host.moduleCheckboxes()) {
            if (cb.isChecked()) {
                hasSelections = true;
                ViewGroup indicatorContainer = (ViewGroup) cb.getParent();
                ViewGroup card = (ViewGroup) indicatorContainer.getParent();
                ModuleRegistry.IiabModule module = (ModuleRegistry.IiabModule) card.getTag();

                if (module != null) {
                    host.installationQueue().add(module.yamlBaseKey);
                }
            }
        }

        btnLaunchInstall.setEnabled(hasSelections);
        btnLaunchInstall.setAlpha(hasSelections ? 1.0f : 0.5f);
        btnLaunchInstall.setText(fragment.getString(R.string.install_btn_launch));

        if (hasSelections) {
            btnLaunchInstall.setOnClickListener(v -> {
                MainActivity mainAct = (MainActivity) fragment.getActivity();
                if (mainAct != null && mainAct.isServerAlive) {
                    Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                    return;
                }
                if (host.isSystemBusy() && !host.isBatchInstalling()) {
                    Snackbar.make(v, host.getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                    return;
                }

                host.setBatchInstalling(true);
                saveQueueToPrefs();
                host.updateDynamicButtons();
                processNextInQueue();
            });
        } else {
            btnLaunchInstall.setOnClickListener(null);
        }
    }

    // ADFA-4435: modules whose runrole failed in the current batch (surfaced when the queue drains).
    private final List<String> failedModules = new ArrayList<>();
    // ADFA-4458: true while a module install is running; blocks re-entry of processNextInQueue
    // (e.g. onResume re-posting it) so a resume cannot finish the already-dequeued module.
    private boolean installInFlight = false;
    // ADFA-4458: module keys the user selected; survives card re-render. verifyInstallationState
    // runs on onResume and used to reset checks, so selection was lost when switching tabs.
    private final java.util.Set<String> selectedModuleKeys = new java.util.HashSet<>();

    public void processNextInQueue() {
        if (installInFlight) return; // ADFA-4458: a module install is in flight; ignore re-entry
        if (host.installationQueue().isEmpty()) {
            host.setBatchInstalling(false);
            saveQueueToPrefs();
            btnLaunchInstall.setEnabled(false);
            btnLaunchInstall.setText(fragment.getString(R.string.install_btn_launch));
            fetchLocalVarsFromPRoot();
            if (fragment.getView() != null) {
                if (failedModules.isEmpty()) {
                    Snackbar.make(fragment.getView(), R.string.install_msg_finished, Snackbar.LENGTH_LONG).show();
                } else {
                    // ADFA-4435: do not report a clean finish when one or more modules failed.
                    Snackbar.make(fragment.getView(),
                            fragment.getString(R.string.install_msg_failed, android.text.TextUtils.join(", ", failedModules)),
                            Snackbar.LENGTH_LONG).show();
                }
            }
            failedModules.clear();
            return;
        }

        String nextModule = host.installationQueue().remove(0);
        saveQueueToPrefs();

        // D2: nextModule is interpolated into a command run as root inside the
        // container (sed/echo/runrole). Only allow names from the known catalog
        // with no shell metacharacters; fail closed and skip anything else.
        if (!ModuleName.isAllowed(nextModule, ModuleRegistry.validYamlKeys())) {
            Log.e(TAG, "Refusing to install unrecognized/unsafe module name: " + nextModule);
            if (fragment.getActivity() instanceof MainActivity) {
                ((MainActivity) fragment.getActivity()).addToLog("[Security] Skipped invalid module: " + nextModule);
            }
            processNextInQueue();
            return;
        }

        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setText(fragment.getString(R.string.install_status_installing_module, nextModule));

        File rootfsDir = new File(fragment.requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        if (host.prootEngine() == null) host.setPRootEngine(new PRootEngine());

        String installCmd = "sed -i -E '/^[[:space:]]*" + nextModule + "_(install|enabled)[[:space:]]*:/d' /etc/iiab/local_vars.yml && " +
                "echo '" + nextModule + "_install: True' >> /etc/iiab/local_vars.yml && " +
                "echo '" + nextModule + "_enabled: True' >> /etc/iiab/local_vars.yml && " +
                "cd /opt/iiab/iiab && ./runrole " + nextModule;

        // ADFA-4435: Ansible can print its failure to stdout yet still exit 0 (e.g. the
        // /dev/shm multiprocessing crash), so watch the output as well as the exit code.
        // The verdict lives in a pure, unit-tested domain object.
        final AnsibleRunOutcome outcome = new AnsibleRunOutcome();
        installInFlight = true; // ADFA-4458: block re-entry while this install runs
        host.prootEngine().executeInContainer(fragment.requireContext(), rootfsDir.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                outcome.observe(line);
                if (fragment.getActivity() instanceof MainActivity)
                    ((MainActivity) fragment.getActivity()).runOnUiThread(() -> ((MainActivity) fragment.getActivity()).addToLog("[Ansible] " + line));
            }

            @Override
            public void onProcessExit(int exitCode) {
                installInFlight = false; // ADFA-4458: this module's install finished
                if (fragment.getActivity() == null) return;
                // ADFA-4435: was 'continue regardless of outcome'. A non-zero exit OR an Ansible
                // error in the output means FAILED: roll back the speculative local_vars edit so
                // the module is not left looking installed/enabled, then surface it.
                final boolean failed = outcome.failed(exitCode);
                if (failed) {
                    failedModules.add(nextModule);
                    if (fragment.getActivity() instanceof MainActivity)
                        ((MainActivity) fragment.getActivity()).runOnUiThread(() -> ((MainActivity) fragment.getActivity())
                                .addToLog("[Install] FAILED: " + nextModule + " (exit=" + exitCode + ")"));
                    revertModuleInLocalVars(nextModule, rootfsDir, () -> {
                        if (fragment.getActivity() != null)
                            fragment.getActivity().runOnUiThread(() -> processNextInQueue());
                    });
                } else {
                    selectedModuleKeys.remove(nextModule); // ADFA-4458: installed -> drop from selection
                    fragment.getActivity().runOnUiThread(() -> processNextInQueue());
                }
            }

            @Override
            public void onError(String error) {
                installInFlight = false; // ADFA-4458
                if (fragment.getActivity() != null) {
                    fragment.getActivity().runOnUiThread(() -> {
                        host.setBatchInstalling(false);
                        host.updateDynamicButtons();
                        if (fragment.getView() != null)
                            Snackbar.make(fragment.getView(), fragment.getString(R.string.install_error_bootstrap, error), Snackbar.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    /**
     * ADFA-4435: Roll back the speculative local_vars edit made before runrole (the sed delete +
     * echo _install/_enabled: True), so a failed install is not left looking installed/enabled.
     * Always invokes {@code then} afterwards, whether or not the revert itself succeeds.
     */
    private void revertModuleInLocalVars(String module, File rootfsDir, Runnable then) {
        if (host.prootEngine() == null) host.setPRootEngine(new PRootEngine());
        String revertCmd = "sed -i -E '/^[[:space:]]*" + module + "_(install|enabled)[[:space:]]*:/d' /etc/iiab/local_vars.yml";
        host.prootEngine().executeInContainer(fragment.requireContext(), rootfsDir.getAbsolutePath(), revertCmd, new PRootEngine.OutputListener() {
            @Override public void onOutputLine(String line) { }
            @Override public void onProcessExit(int exitCode) { then.run(); }
            @Override public void onError(String error) { then.run(); }
        });
    }

    private void saveQueueToPrefs() {
        if (fragment.getActivity() == null) return;
        android.content.SharedPreferences prefs = fragment.getActivity().getSharedPreferences("iiab_queue_prefs", android.content.Context.MODE_PRIVATE);
        String queueString = android.text.TextUtils.join(",", host.installationQueue());
        prefs.edit().putString("pending_modules", queueString).putBoolean("is_batch_installing", host.isBatchInstalling()).apply();
    }

    private void abortInstallation(String message) {
        InstallProgressRepository.get().postIdle();
        host.disableSystemProtection();
        host.setDownloadingRootfs(false);
        if (fragment.isAdded() && fragment.getActivity() != null) {
            fragment.getActivity().runOnUiThread(() -> {
                btnFastInstall.stopProgress();
                btnFastInstall.setText(R.string.install_btn_install);
                btnFastInstall.setAlpha(1.0f);
                host.updateDynamicButtons();
                if (fragment.getView() != null)
                    Snackbar.make(fragment.getView(), message, Snackbar.LENGTH_LONG).show();
            });
        }
    }

    private void finishInstallationSuccess() {
        InstallProgressRepository.get().postIdle();
        host.disableSystemProtection();
        host.setDownloadingRootfs(false);
        if (fragment.isAdded() && fragment.getActivity() != null) {
            fragment.getActivity().runOnUiThread(() -> {
                btnFastInstall.stopProgress();
                btnFastInstall.setText(R.string.install_btn_reinstall);
                btnFastInstall.setAlpha(1.0f);
                host.updateDynamicButtons();
                host.requestFreshLocalVarsSilently();
                if (fragment.getView() != null)
                    Snackbar.make(fragment.getView(), R.string.install_success_deployment, Snackbar.LENGTH_LONG).show();
            });
        }
    }

    private void downloadAndIndexKiwix(String zimFilename, File debianRootfs) {
        if (fragment.getActivity() == null) return;
        fragment.getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_preparing_kiwix));

        String zimUrl = "https://download.kiwix.org/zim/wikipedia/" + zimFilename;
        File libraryDir = new File(debianRootfs, "library/zims/content");
        if (!libraryDir.exists()) libraryDir.mkdirs();

        if (host.aria2Manager() == null) host.setAria2Manager(new Aria2Manager());
        host.aria2Manager().startDownload(fragment.requireContext(), zimUrl, new Aria2Manager.DownloadListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta) {
                if (fragment.isAdded() && fragment.getActivity() != null)
                    fragment.getActivity().runOnUiThread(() -> btnFastInstall.setText(fragment.getString(R.string.install_status_zim_download, percentage, speed)));
            }

            @Override
            public void onComplete(String downloadPath) {
                if (fragment.getActivity() == null) return;
                fragment.getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_indexing_zim));
                File downloadedZim = new File(downloadPath, zimFilename);
                if (downloadedZim.exists())
                    downloadedZim.renameTo(new File(libraryDir, zimFilename));

                if (host.prootEngine() == null) host.setPRootEngine(new PRootEngine());
                host.prootEngine().executeInContainer(fragment.requireContext(), debianRootfs.getAbsolutePath(), "iiab-make-kiwix-lib", new PRootEngine.OutputListener() {
                    @Override
                    public void onOutputLine(String line) {
                        if (fragment.getActivity() instanceof MainActivity)
                            ((MainActivity) fragment.getActivity()).runOnUiThread(() -> ((MainActivity) fragment.getActivity()).addToLog("[Kiwix] " + line));
                    }

                    @Override
                    public void onProcessExit(int exitCode) {
                        runMapsAnsible(debianRootfs);
                    }

                    @Override
                    public void onError(String error) {
                        runMapsAnsible(debianRootfs);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runMapsAnsible(debianRootfs);
            }
        });
    }

    private void runMapsAnsible(File debianRootfs) {
        if (fragment.getActivity() == null) return;

        InstallationPlanner.Tier safeTier = (host.getSelectedTier() != null) ? host.getSelectedTier() : InstallationPlanner.Tier.BASIC;

        if (safeTier == InstallationPlanner.Tier.BASIC) {
            // BYPASS PARA BASIC
            fragment.getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_maps_provisioned));
            new Handler(Looper.getMainLooper()).postDelayed(this::finishInstallationSuccess, 1500);
            return;
        }

        fragment.getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_maps_configuring));

        if (host.prootEngine() == null) host.setPRootEngine(new PRootEngine());
        String installCmd = "cd /opt/iiab/iiab && ./runrole --reinstall maps";

        host.prootEngine().executeInContainer(fragment.requireContext(), debianRootfs.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                if (fragment.getActivity() instanceof MainActivity) {
                    ((MainActivity) fragment.getActivity()).runOnUiThread(() -> ((MainActivity) fragment.getActivity()).addToLog("[Ansible] " + line));
                }
            }

            @Override
            public void onProcessExit(int exitCode) {
                finishInstallationSuccess();
            }

            @Override
            public void onError(String error) {
                finishInstallationSuccess();
            }
        });
    }

    private void editLocalVarsForMaps(File debianRootfs, InstallationPlanner.Tier tier) {
        File yamlFile = new File(debianRootfs, "etc/iiab/local_vars.yml");
        if (!yamlFile.exists()) return;
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(yamlFile));
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append("\n");
            reader.close();

            String text = content.toString();
            // Always make sure the installation and module are active
            text = text.replaceAll("(?m)^maps_install:\\s*.*", "maps_install: True");
            text = text.replaceAll("(?m)^maps_enabled:\\s*.*", "maps_enabled: True");

            if (tier == InstallationPlanner.Tier.BASIC) {
                // BASIC TIER ~0.2GB
                // Note: The current base image already has these default values.
                // We leave them commented for the future in case the base image changes.
                /*
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: nat-z8");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 7");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: none");
                */
            } else if (tier == InstallationPlanner.Tier.STANDARD) {
                // STANDARD TIER ~11GB
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: osm-z11");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 7");
            } else if (tier == InstallationPlanner.Tier.FULL) {
                // FULL TIER ~16GB
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: osm-z11");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 8");
            }

            java.io.FileWriter writer = new java.io.FileWriter(yamlFile);
            writer.write(text);
            writer.close();
        } catch (Exception ignored) {
        }
    }

    public void fetchLocalVarsFromPRoot() {
        File rootfsDir = new File(fragment.requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        File localVarsFile = new File(rootfsDir, "etc/iiab/local_vars.yml");

        if (!rootfsDir.exists() || !rootfsDir.isDirectory() || !localVarsFile.exists()) {
            host.setLastKnownState(new JSONObject());
            verifyInstallationState(host.getLastKnownState());
            return;
        }

        new Thread(() -> {
            try {
                StringBuilder yamlOutput = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(localVarsFile));
                String line;
                while ((line = br.readLine()) != null) {
                    yamlOutput.append(line).append("\n");
                }
                br.close();

                JSONObject freshVars = parseYamlToJson(yamlOutput.toString());
                host.setLastKnownState(freshVars);

                if (fragment.getActivity() instanceof MainActivity) {
                    fragment.getActivity().getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_module_state_trusted", true).apply();
                }

                if (fragment.getActivity() != null) {
                    fragment.getActivity().runOnUiThread(() -> verifyInstallationState(freshVars));
                }
            } catch (Exception e) {
                if (fragment.getActivity() != null) {
                    fragment.getActivity().runOnUiThread(() -> verifyInstallationState(host.getLastKnownState()));
                }
            }
        }).start();
    }

    public void verifyInstallationState(JSONObject jsonVars) {
        new Thread(() -> {
            if (!fragment.isAdded() || fragment.getActivity() == null || rolesContainer == null) return;

            boolean isMainServerAlive = host.pingUrl("http://localhost:8085/home");
            boolean discrepancyFound = false;

            for (int r = 0; r < rolesContainer.getChildCount(); r++) {
                LinearLayout row = (LinearLayout) rolesContainer.getChildAt(r);
                for (int c = 0; c < row.getChildCount(); c++) {
                    LinearLayout card = (LinearLayout) row.getChildAt(c);
                    ModuleRegistry.IiabModule module = (ModuleRegistry.IiabModule) card.getTag();
                    if (module == null) continue;

                    android.widget.FrameLayout indicatorContainer = (android.widget.FrameLayout) card.getChildAt(0);
                    View led = indicatorContainer.getChildAt(0);
                    CheckBox checkBox = (CheckBox) indicatorContainer.getChildAt(1);

                    boolean isInstallTrue = jsonVars.optBoolean(module.yamlBaseKey + "_install", false);
                    boolean isEnabledTrue = jsonVars.optBoolean(module.yamlBaseKey + "_enabled", false);
                    boolean yamlState = isInstallTrue || isEnabledTrue;
                    boolean pingState = isMainServerAlive && host.pingUrl("http://localhost:8085/" + module.endpoint);

                    MainActivity mainAct = (MainActivity) fragment.getActivity();
                    boolean isRunning = mainAct != null && mainAct.isServerAlive;
                    boolean isTrusted = mainAct != null && mainAct.isModuleStateTrusted();

                    boolean isConfirmedInstalled;
                    boolean isDiscrepancy;

                    if (isRunning) {
                        isConfirmedInstalled = yamlState && pingState;
                        isDiscrepancy = yamlState != pingState;
                    } else {
                        isConfirmedInstalled = yamlState;
                        isDiscrepancy = yamlState && !isTrusted;
                    }

                    final boolean finalConfirmed = isConfirmedInstalled;
                    final boolean finalDiscrepancyFlag = isDiscrepancy;
                    final boolean finalIsRunning = isRunning;
                    final String moduleKey = module.yamlBaseKey;

                    fragment.getActivity().runOnUiThread(() -> {
                        card.setOnClickListener(null);
                        checkBox.setOnCheckedChangeListener(null);

                        if (finalConfirmed && !finalDiscrepancyFlag) {
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundTintList(null);

                            if (finalIsRunning) {
                                led.setBackgroundResource(R.drawable.led_on_green);
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_confirmed, Snackbar.LENGTH_LONG).show());
                            } else {
                                led.setBackgroundResource(R.drawable.led_on_green);
                                led.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.accent_secondary)));
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_offline_trusted, Snackbar.LENGTH_LONG).show());
                            }
                        } else if (finalDiscrepancyFlag) {
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundResource(R.drawable.led_off);
                            led.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_pending)));
                            card.setOnClickListener(v -> Snackbar.make(v, R.string.install_warning_discrepancy_msg, Snackbar.LENGTH_LONG).show());
                        } else {
                            led.setVisibility(View.GONE);
                            checkBox.setVisibility(View.VISIBLE);
                            checkBox.setChecked(selectedModuleKeys.contains(moduleKey)); // ADFA-4458: restore selection

                            if (finalIsRunning) {
                                checkBox.setEnabled(false);
                                card.setAlpha(0.6f);
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show());
                            } else {
                                checkBox.setEnabled(true);
                                card.setAlpha(1.0f);
                                checkBox.setButtonTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.text_primary)));
                                card.setOnClickListener(v -> checkBox.toggle());
                            }

                            if (!host.moduleCheckboxes().contains(checkBox))
                                host.moduleCheckboxes().add(checkBox);
                            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                                if (isChecked) selectedModuleKeys.add(moduleKey); else selectedModuleKeys.remove(moduleKey);
                                evaluateLaunchButton();
                            });
                        }
                    });

                    if (finalDiscrepancyFlag) discrepancyFound = true;
                }
            }

            final boolean finalDiscrepancy = discrepancyFound;
            fragment.getActivity().runOnUiThread(() -> {
                if (discrepancyWarning != null)
                    discrepancyWarning.setVisibility(finalDiscrepancy ? View.VISIBLE : View.GONE);
                evaluateLaunchButton();
            });

        }).start();
    }

    private JSONObject parseYamlToJson(String yaml) {
        // Delegates to the pure, unit-tested util (extracted from this god class).
        // The naive split-on-':' behavior is unchanged; replacing it with a real
        // YAML parser is still tracked as tech-debt D14.
        return LocalVarsYamlParser.parseToJson(yaml);
    }
}
