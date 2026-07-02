/*
 * ============================================================================
 * Name        : InstallController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Install UI controller carved out of DeployFragment (strangler-fig,
 *               ADFA-4434 PR 2). It owns the install button validations + dialogs,
 *               the module install queue (per-role provisioning) and the
 *               installation-state verification. The long-running rootfs install
 *               pipeline (download + extract + companion data) was moved into the
 *               lifecycle-independent foreground InstallService (ADFA-4474 PR2),
 *               so this controller only STARTS it and the UI observes progress
 *               through InstallProgressRepository. Shared state stays on the
 *               Fragment via InstallHost.
 *               See controller/docs/TECH_DEBT_PLAN.md.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import org.iiab.controller.MainActivity;
import org.iiab.controller.ModuleRegistry;
import org.iiab.controller.PRootEngine;
import org.iiab.controller.ProgressButton;
import org.iiab.controller.R;
import org.iiab.controller.deploy.domain.ModuleName;
import org.iiab.controller.install.domain.AnsibleRunOutcome;
import org.iiab.controller.util.LocalVarsYamlParser;

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
            // up front (but still allow cancelling an in-progress install below).
            if (!host.hasInternet() && !host.isDownloadingRootfs()) {
                Snackbar.make(v, R.string.install_msg_no_connection, Snackbar.LENGTH_LONG).show();
                return;
            }

            // 2. HIGH PRIORITY: if an install is in flight, this button cancels it.
            // The InstallService handles the cancel and posts the terminal state; the
            // observer in DeployFragment resets the button + shows the snackbar.
            if (host.isDownloadingRootfs()) {
                new android.app.AlertDialog.Builder(fragment.requireContext())
                        .setTitle(fragment.getString(R.string.install_btn_cancel_title))
                        .setMessage(fragment.getString(R.string.install_btn_cancel_msg))
                        .setPositiveButton(fragment.getString(R.string.install_btn_cancel_confirm), (dialog, which) -> {
                            Intent cancel = new Intent(fragment.requireContext(), InstallService.class)
                                    .setAction(InstallService.ACTION_CANCEL);
                            fragment.requireContext().startService(cancel);
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

            // 5. Start the installation in the foreground service (survives recreation).
            if (debianRootfs.exists() && debianRootfs.isDirectory()) {
                new android.app.AlertDialog.Builder(fragment.requireContext())
                        .setTitle(R.string.install_btn_reinstall)
                        .setMessage(R.string.install_dialog_wipe_msg)
                        .setPositiveButton(R.string.install_btn_yes, (dialog, which) -> startInstallService(true))
                        .setNegativeButton(R.string.install_btn_no, null)
                        .show();
            } else {
                startInstallService(false);
            }
        });
    }

    /** Snapshots the current selections and hands the long-running install to the service. */
    private void startInstallService(boolean reinstall) {
        Context ctx = fragment.requireContext();
        Intent i = new Intent(ctx, InstallService.class);
        i.setAction(InstallService.ACTION_START);
        i.putExtra(InstallService.EXTRA_TIER, host.getSelectedTier() != null ? host.getSelectedTier().name() : null);
        i.putExtra(InstallService.EXTRA_COMPANION, chkCompanionData.isChecked());
        i.putExtra(InstallService.EXTRA_ARCH, host.getTermuxArch());
        i.putExtra(InstallService.EXTRA_KIWIX_LANG, host.getOverrideKiwixLang());
        i.putExtra(InstallService.EXTRA_KIWIX_VARIANT, host.getOverrideKiwixVariant());
        i.putExtra(InstallService.EXTRA_REINSTALL, reinstall);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
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
    // ADFA-4458 / ADFA-4476 slice 1: module keys the user selected. Moved to the
    // Activity-scoped DownloadStateViewModel (via host.selectedModuleKeys()) so the
    // selection now also survives a recreation, not just a card re-render.

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
                    host.selectedModuleKeys().remove(nextModule); // ADFA-4458: installed -> drop from selection
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
                            checkBox.setChecked(host.selectedModuleKeys().contains(moduleKey)); // ADFA-4458: restore selection

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
                                if (isChecked) host.selectedModuleKeys().add(moduleKey); else host.selectedModuleKeys().remove(moduleKey);
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
