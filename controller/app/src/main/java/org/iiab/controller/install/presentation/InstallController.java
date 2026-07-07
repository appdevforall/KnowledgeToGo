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
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import org.iiab.controller.util.Snackbars;

import org.iiab.controller.MainActivity;
import org.iiab.controller.ModuleRegistry;
import org.iiab.controller.ProgressButton;
import org.iiab.controller.R;
import org.iiab.controller.util.LocalVarsYamlParser;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class InstallController {

    private final Fragment fragment;
    private final InstallHost host;

    private MainActivity mainAct;
    private File debianRootfs;
    private File iiabRootDir;
    private ProgressButton btnFastInstall;
    private ProgressButton btnLaunchInstall;
    private LinearLayout discrepancyWarning;
    private LinearLayout rolesContainer;
    private CheckBox chkCompanionData;

    public InstallController(Fragment fragment, InstallHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Wire the install/fast-install buttons. Call from onViewCreated. */
    public void bind(MainActivity mainAct, File debianRootfs, File iiabRootDir,
                     ProgressButton btnFastInstall, ProgressButton btnLaunchInstall,
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
            if (org.iiab.controller.ServerStateRepository.get().current().alive) {
                Snackbars.make(v, R.string.install_msg_server_running_lock).show();
                return;
            }

            // 1b. No internet: a fresh install requires downloading the rootfs. Block it
            // up front (but still allow cancelling an in-progress install below).
            if (!host.hasInternet() && !host.isDownloadingRootfs()) {
                Snackbars.make(v, R.string.install_msg_no_connection).show();
                return;
            }

            // 2. HIGH PRIORITY: if an install is in flight, this button cancels it.
            // The InstallService handles the cancel and posts the terminal state; the
            // observer in DeployFragment resets the button + shows the snackbar.
            if (host.isDownloadingRootfs()
                    && InstallProgressRepository.get().currentOp() == InstallState.Op.INSTALL) {
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
                Snackbars.make(v, host.getSystemBusyMessage()).show();
                return;
            }

            // 4. Normal installation startup validations
            if (host.getSelectedTier() == null) {
                Snackbars.make(v, R.string.install_error_no_tier).show();
                return;
            }
            if (!host.isStorageSafe()) {
                Snackbars.make(v, R.string.install_error_no_storage).show();
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
        // ADFA-4466 Phase 1: operational analytics (no-op unless the operator opted in).
        org.iiab.controller.analytics.AnalyticsClient.with(ctx).logInstallStarted(
                host.getSelectedTier() != null ? host.getSelectedTier().name() : null,
                chkCompanionData.isChecked(), host.getTermuxArch());
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
                if (mainAct != null && org.iiab.controller.ServerStateRepository.get().current().alive) {
                    Snackbars.make(v, R.string.install_msg_server_running_lock).show();
                    return;
                }
                if (host.isSystemBusy() && !host.isBatchInstalling()) {
                    Snackbars.make(v, host.getSystemBusyMessage()).show();
                    return;
                }

                startModuleQueue();
            });
        } else {
            btnLaunchInstall.setOnClickListener(null);
        }
    }

    /**
     * ADFA-4476 slice 3: hand the selected module queue to the foreground InstallService,
     * which owns the dequeue loop (sed/echo/runrole, AnsibleRunOutcome verdict, revert-on-fail)
     * and publishes progress to ModuleQueueRepository. The service, not this Fragment-scoped
     * controller, is the single owner, so a recreation mid-queue cannot launch a second
     * concurrent runrole -- this supersedes the ADFA-4458/4519 re-entry guard and the
     * onResume() re-fire. DeployFragment observes the repository for the grid + snackbars.
     */
    public void startModuleQueue() {
        java.util.List<String> queue = host.installationQueue();
        if (queue == null || queue.isEmpty()) return;

        Context ctx = fragment.requireContext();
        Intent i = new Intent(ctx, InstallService.class);
        i.setAction(InstallService.ACTION_START_MODULES);
        i.putExtra(InstallService.EXTRA_MODULES, queue.toArray(new String[0]));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }

        host.updateDynamicButtons();
        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setText(fragment.getString(R.string.install_btn_launch));
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
                    boolean isRunning = mainAct != null && org.iiab.controller.ServerStateRepository.get().current().alive;
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
                    // ADFA-4519: the app itself is installing this module right now. This is our
                    // own authoritative state (survives recreation), so it wins over the yaml read
                    // -- otherwise a theme toggle mid-install re-reads local_vars.yml (where
                    // '<mod>_install: True' was written at runrole START) and falsely shows it done.
                    final boolean finalIsInstalling = ModuleQueueRepository.get().isInstalling(moduleKey);

                    fragment.getActivity().runOnUiThread(() -> {
                        card.setOnClickListener(null);
                        checkBox.setOnCheckedChangeListener(null);

                        if (finalIsInstalling) {
                            // In progress: keep it as a locked, checked selection -- never "installed".
                            led.setVisibility(View.GONE);
                            checkBox.setVisibility(View.VISIBLE);
                            checkBox.setChecked(true);
                            checkBox.setEnabled(false);
                            card.setAlpha(0.6f);
                            card.setOnClickListener(v -> Snackbars.make(v,
                                    fragment.getString(R.string.install_status_installing_module, moduleKey)).show());
                        } else if (finalConfirmed && !finalDiscrepancyFlag) {
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundTintList(null);

                            if (finalIsRunning) {
                                led.setBackgroundResource(R.drawable.led_on_green);
                                card.setOnClickListener(v -> Snackbars.make(v, R.string.install_msg_confirmed).show());
                            } else {
                                led.setBackgroundResource(R.drawable.led_on_green);
                                led.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.accent_secondary)));
                                card.setOnClickListener(v -> Snackbars.make(v, R.string.install_msg_offline_trusted).show());
                            }
                        } else if (finalDiscrepancyFlag) {
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundResource(R.drawable.led_off);
                            led.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_pending)));
                            card.setOnClickListener(v -> Snackbars.make(v, R.string.install_warning_discrepancy_msg).show());
                        } else {
                            led.setVisibility(View.GONE);
                            checkBox.setVisibility(View.VISIBLE);
                            checkBox.setChecked(host.selectedModuleKeys().contains(moduleKey)); // ADFA-4458: restore selection

                            if (finalIsRunning) {
                                checkBox.setEnabled(false);
                                card.setAlpha(0.6f);
                                card.setOnClickListener(v -> Snackbars.make(v, R.string.install_msg_server_running_lock).show());
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
