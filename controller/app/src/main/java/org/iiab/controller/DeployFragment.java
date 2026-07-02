/*
 * ============================================================================
 * Name        : DeployFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Installation / deployment view (Refactored with SAF & Regions)
 * ============================================================================
 */
package org.iiab.controller;

import org.iiab.controller.deploy.domain.ModuleName;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.snackbar.Snackbar;

import org.iiab.controller.util.LocalVarsYamlParser;
import org.iiab.controller.rootfs.domain.RootfsAbi;
import org.iiab.controller.rootfs.domain.RootfsTier;
import org.iiab.controller.rootfs.presentation.RootfsUiState;
import org.iiab.controller.rootfs.presentation.RootfsViewModel;
import org.iiab.controller.rootfs.presentation.RootfsViewModelFactory;
import org.iiab.controller.util.ByteFormatter;
import org.iiab.controller.util.ProcessRunner;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DeployFragment extends Fragment implements org.iiab.controller.backup.presentation.BackupHost,
        org.iiab.controller.install.presentation.PlannerHost,
        org.iiab.controller.install.presentation.InstallHost,
        org.iiab.controller.install.presentation.ResetDeleteHost,
        org.iiab.controller.install.presentation.AdbShareHost {

    private final org.iiab.controller.install.presentation.AdbShareController adbShareController =
            new org.iiab.controller.install.presentation.AdbShareController(this, this);

    private final org.iiab.controller.install.presentation.ResetDeleteController resetDeleteController =
            new org.iiab.controller.install.presentation.ResetDeleteController(this, this);

    private final org.iiab.controller.install.presentation.InstallController installController =
            new org.iiab.controller.install.presentation.InstallController(this, this);

    private final org.iiab.controller.install.presentation.PlannerController plannerController =
            new org.iiab.controller.install.presentation.PlannerController(this, this);

    private final org.iiab.controller.backup.presentation.BackupController backupController =
            new org.iiab.controller.backup.presentation.BackupController(this, this);

    // =========================================================================================
    // REGION 1: VARIABLES & STATE
    // =========================================================================================
    private static final String TAG = "IIAB-DeployFragment";

    // UI Variables
    private View ledInternet, ledDevMode, ledDcpr, ledPpk;
    private TextView txtDcpr, txtPpk, btnRefreshModules;
    private LinearLayout rolesContainer, discrepancyWarning;
    private Button btnLaunchInstall, btnAdvancedReset;
    private ProgressButton btnFastInstall, btnFastDelete;
    private Button btnAdvancedForceStop;
    private ProgressButton btnAdvancedBackup, btnAdvancedRestore;
    private LinearLayout restoreLogPanel;
    private TextView restoreLogText, restoreLogResult;
    private androidx.core.widget.NestedScrollView restoreLogScroll;

    // Backup Menu UI
    private TextView txtSelectBackupTitle, txtBackupStatus;
    private LinearLayout containerBackupList;

    // Advanced Monitoring UI
    private TextView txtAdvMonitoringTitle;
    private LinearLayout containerAdvMonitoring;
    private Button btnAdbAction;
    private View ledAdbStatus;
    private TextView txtAdbLedLabel;
    private com.github.mikephil.charting.charts.LineChart cpuChart;

    // Planner UI
    private Button btnTierBasic, btnTierStandard, btnTierFull;
    private TextView txtLegendIiab, txtLegendMaps, txtLegendKiwix, txtLegendFree;
    private TextView txtOfflineEstimate;
    private CheckBox chkCompanionData;
    private MultiResourceGaugeView storageGauge;
    private android.widget.ImageButton btnKiwixSettings;

    // SAF & Backup Controls
    private Button btnImportBackup;
    private boolean isBackupInProgress = false;

    // State Variables
    private final List<CheckBox> newInstallCheckboxes = new ArrayList<>();
    private File sharedStateDir;
    private JSONObject lastKnownState = new JSONObject();
    private List<String> installationQueue = new ArrayList<>();
    private boolean isBatchInstalling = false;
    private boolean isStorageSafe = false;
    // Presentation-layer source of the OS rootfs size (live, with offline fallback).
    // Last known connectivity, refreshed by checkInternetAccess() (every 3s via liveStatusRunnable).
    private volatile boolean hasInternet = true;

    // Native Engine Variables
    // Download state (Aria2Manager + in-flight flag) is owned by an Activity-scoped
    // ViewModel so it survives Fragment recreation (e.g. rotation during a download)
    // without static mutable state. (ADFA-4459, D9)
    private org.iiab.controller.install.presentation.DownloadStateViewModel downloadState;

    // ADFA-4474 PR2: install progress is owned by InstallService and observed here.
    private boolean installProgressShown = false;       // guards ProgressButton.startProgress()
    private long lastInstallTerminalSeq = -1L;          // fires terminal snackbars exactly once
    private final BroadcastReceiver installLogReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String line = intent.getStringExtra(org.iiab.controller.install.presentation.InstallService.EXTRA_LINE);
            if (line != null && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).addToLog(line);
            }
        }
    };
    // State Variables (New control variables)
    private boolean isRestoring = false;
    private boolean isDeleting = false;
    private boolean isImporting = false;
    private PRootEngine prootEngine;

    // Background Handlers
    private final Handler liveStatusHandler = new Handler(Looper.getMainLooper());
    private Runnable liveStatusRunnable;

    // ADB Variables




    // =========================================================================================
    // REGION 2: ANDROID LIFECYCLE
    // =========================================================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_deploy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        downloadState = new ViewModelProvider(requireActivity())
                .get(org.iiab.controller.install.presentation.DownloadStateViewModel.class);
        downloadState.installState().observe(getViewLifecycleOwner(), this::renderInstallProgress);

        // UI Binding
        ledInternet = view.findViewById(R.id.led_install_internet);
        ledDevMode = view.findViewById(R.id.led_install_dev_mode);
        ledDcpr = view.findViewById(R.id.led_install_dcpr);
        ledPpk = view.findViewById(R.id.led_install_ppk);
        txtDcpr = view.findViewById(R.id.txt_install_dcpr);
        txtPpk = view.findViewById(R.id.txt_install_ppk);
        btnAdbAction = view.findViewById(R.id.btn_adb_action);
        ledAdbStatus = view.findViewById(R.id.led_adb_status);
        txtAdbLedLabel = view.findViewById(R.id.txt_adb_led_label);
        cpuChart = view.findViewById(R.id.cpu_chart);
        btnKiwixSettings = view.findViewById(R.id.btn_kiwix_settings);
        rolesContainer = view.findViewById(R.id.install_roles_container);
        discrepancyWarning = view.findViewById(R.id.install_discrepancy_warning);
        btnLaunchInstall = view.findViewById(R.id.btn_launch_install);
        btnFastInstall = view.findViewById(R.id.btn_fast_install);
        btnFastDelete = view.findViewById(R.id.btn_fast_delete);
        btnAdvancedReset = view.findViewById(R.id.btn_advanced_reset);
        btnAdvancedBackup = view.findViewById(R.id.btn_advanced_backup);
        btnAdvancedRestore = view.findViewById(R.id.btn_advanced_restore);
        btnAdvancedForceStop = view.findViewById(R.id.btn_advanced_force_stop);
        restoreLogPanel = view.findViewById(R.id.restore_log_panel);
        restoreLogText = view.findViewById(R.id.restore_log_text);
        restoreLogResult = view.findViewById(R.id.restore_log_result);
        restoreLogScroll = view.findViewById(R.id.restore_log_scroll);
        View restoreLogClose = view.findViewById(R.id.restore_log_close);
        if (restoreLogClose != null) {
            restoreLogClose.setOnClickListener(vv -> { if (restoreLogPanel != null) restoreLogPanel.setVisibility(View.GONE); });
        }
        txtSelectBackupTitle = view.findViewById(R.id.txt_select_backup_title);
        containerBackupList = view.findViewById(R.id.container_backup_list);
        txtBackupStatus = view.findViewById(R.id.txt_backup_status);
        btnRefreshModules = view.findViewById(R.id.btn_refresh_modules);
        btnTierBasic = view.findViewById(R.id.btn_tier_basic);
        btnTierStandard = view.findViewById(R.id.btn_tier_standard);
        btnTierFull = view.findViewById(R.id.btn_tier_full);
        chkCompanionData = view.findViewById(R.id.chk_companion_data);
        storageGauge = view.findViewById(R.id.storage_projection_gauge);
        txtLegendIiab = view.findViewById(R.id.txt_legend_iiab);
        txtLegendMaps = view.findViewById(R.id.txt_legend_maps);
        txtLegendKiwix = view.findViewById(R.id.txt_legend_kiwix);
        txtLegendFree = view.findViewById(R.id.txt_legend_free);
        txtOfflineEstimate = view.findViewById(R.id.txt_offline_estimate);

        // SAF Binding
        btnImportBackup = view.findViewById(R.id.btn_import_backup);

        sharedStateDir = new File(Environment.getExternalStorageDirectory(), ".iiab_state");

        // Initialization Logic
        backupController.registerLaunchers();
        adbShareController.onViewCreated(ledAdbStatus, ledDcpr, ledPpk, txtDcpr, txtPpk, txtAdbLedLabel, btnAdbAction);
        setupAdvancedMonitoringMenu(view);
        setupCpuChart();
        plannerController.bind(rolesContainer, storageGauge, btnTierBasic, btnTierStandard, btnTierFull,
                txtLegendIiab, txtLegendMaps, txtLegendKiwix, txtLegendFree, txtOfflineEstimate,
                btnKiwixSettings, chkCompanionData);
        setupAllCollapsibleMenus();
        plannerController.createModulesGrid();

        // Initial States
        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setAlpha(0.5f);
        btnKiwixSettings.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        btnFastInstall.setAlpha(0.4f);

        // Handlers
        liveStatusRunnable = () -> {
            new Thread(() -> {
                boolean isAlive = pingUrl("http://localhost:8085/home");
                checkInternetAccess();
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).isServerAlive = isAlive;
                }
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(this::updateDynamicButtons);
                }
            }).start();
            liveStatusHandler.postDelayed(liveStatusRunnable, 3000);
        };

        requestFreshLocalVarsSilently();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (discrepancyWarning != null) discrepancyWarning.setVisibility(View.GONE);

        adbShareController.onResume();
        checkAndHandleSyncFragmentFocus();
        restoreQueueFromPrefs();

        if (isBatchInstalling) {
            new Handler(Looper.getMainLooper()).postDelayed(installController::processNextInQueue, 500);
        }

        if (lastKnownState.length() > 0) {
            installController.verifyInstallationState(lastKnownState);
        } else {
            loadLocalVarsFallback();
        }

        updateDynamicButtons();
        liveStatusHandler.post(liveStatusRunnable);

        // ADFA-4474 PR2: show InstallService provisioning output in the in-app log while visible.
        androidx.core.content.ContextCompat.registerReceiver(requireContext(), installLogReceiver,
                new IntentFilter(org.iiab.controller.install.presentation.InstallService.ACTION_INSTALL_LOG),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onPause() {
        super.onPause();
        adbShareController.onPause();
        liveStatusHandler.removeCallbacks(liveStatusRunnable);
        try { requireContext().unregisterReceiver(installLogReceiver); } catch (IllegalArgumentException ignored) { }
    }

    /** Renders the observable install progress published by InstallService.
     *  Re-binds automatically after a recreation or backgrounding (ADFA-4474 PR2). */
    private void renderInstallProgress(org.iiab.controller.install.presentation.InstallState s) {
        if (s == null || btnFastInstall == null) return;
        org.iiab.controller.install.presentation.InstallState.Phase p = s.phase;

        if (s.isRunning() && !installProgressShown) {
            installProgressShown = true;
            btnFastInstall.setAlpha(0.8f);
            btnFastInstall.setTextSize(12f);
            btnFastInstall.startProgress();
        }

        switch (p) {
            case DOWNLOADING:
                btnFastInstall.setText(getString(R.string.install_status_os_download, s.percent, s.speed));
                break;
            case EXTRACTING:
            case PROVISIONING:
                btnFastInstall.setText(s.message);
                break;
            case SUCCESS:
                installProgressShown = false;
                btnFastInstall.stopProgress();
                btnFastInstall.setText(R.string.install_btn_reinstall);
                btnFastInstall.setAlpha(1.0f);
                updateDynamicButtons();
                if (s.seq > lastInstallTerminalSeq) {
                    lastInstallTerminalSeq = s.seq;
                    requestFreshLocalVarsSilently();
                    if (getView() != null)
                        Snackbar.make(getView(), R.string.install_success_deployment, Snackbar.LENGTH_LONG).show();
                }
                break;
            case FAILED:
                installProgressShown = false;
                btnFastInstall.stopProgress();
                btnFastInstall.setText(R.string.install_btn_install);
                btnFastInstall.setAlpha(1.0f);
                updateDynamicButtons();
                if (s.seq > lastInstallTerminalSeq) {
                    lastInstallTerminalSeq = s.seq;
                    if (getView() != null && !s.message.isEmpty())
                        Snackbar.make(getView(), s.message, Snackbar.LENGTH_LONG).show();
                }
                break;
            case IDLE:
            default:
                installProgressShown = false;
                break;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // ADFA-4474 PR2: the install now runs in InstallService and must survive
        // leaving this screen (and configuration changes). Cancellation is explicit
        // via the button / notification, so we no longer stop it here.
    }


    // =========================================================================================
    // REGION 3: UI & MENU CONTROLLERS
    // =========================================================================================

    private void setupAllCollapsibleMenus() {
        if (getView() == null) return;
        TextView txtModuleMgmtTitle = getView().findViewById(R.id.txt_module_mgmt_title);
        LinearLayout containerModuleMgmt = getView().findViewById(R.id.container_module_mgmt);
        setupSingleMenu(txtModuleMgmtTitle, containerModuleMgmt, R.string.install_header_roles);

        TextView txtMaintenanceTitle = getView().findViewById(R.id.txt_maintenance_title);
        LinearLayout containerMaintenance = getView().findViewById(R.id.container_maintenance);
        setupSingleMenu(txtMaintenanceTitle, containerMaintenance, R.string.install_header_maintenance);
    }

    private void setupSingleMenu(TextView titleView, View container, int stringRes) {
        if (titleView == null || container == null) return;
        container.setVisibility(View.GONE);
        String baseText = getString(stringRes);
        titleView.setText(getString(R.string.label_separator_up, baseText));
        titleView.setOnClickListener(v -> {
            boolean isCollapsed = container.getVisibility() == View.GONE;
            container.setVisibility(isCollapsed ? View.VISIBLE : View.GONE);
            titleView.setText(getString(isCollapsed ? R.string.label_separator_down : R.string.label_separator_up, baseText));
        });
    }

    private void setupAdvancedMonitoringMenu(View view) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            view.findViewById(R.id.section_adv_monitoring).setVisibility(View.GONE);
            View adbLedsContainer = view.findViewById(R.id.container_adb_leds);
            if (adbLedsContainer != null) adbLedsContainer.setVisibility(View.GONE);
        } else {
            txtAdvMonitoringTitle = view.findViewById(R.id.txt_adv_monitoring_title);
            containerAdvMonitoring = view.findViewById(R.id.container_adv_monitoring);
            setupSingleMenu(txtAdvMonitoringTitle, containerAdvMonitoring, R.string.install_adv_monitoring_title);
        }
    }

    private void focusAdvancedMonitoring() {
        if (containerAdvMonitoring != null && txtAdvMonitoringTitle != null) {
            if (containerAdvMonitoring.getVisibility() == View.GONE)
                txtAdvMonitoringTitle.performClick();
            android.animation.ArgbEvaluator evaluator = new android.animation.ArgbEvaluator();
            android.animation.ObjectAnimator animator = android.animation.ObjectAnimator.ofObject(
                    txtAdvMonitoringTitle, "textColor", evaluator,
                    ContextCompat.getColor(requireContext(), R.color.status_danger), ContextCompat.getColor(requireContext(), R.color.dash_text_primary)
            );
            animator.setDuration(400);
            animator.setRepeatCount(5);
            animator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            animator.start();
        }
    }

    private void checkAndHandleSyncFragmentFocus() {
        android.content.SharedPreferences adbPrefs = requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE);
        if (adbPrefs.getBoolean("pairing_just_succeeded", false)) {
            adbPrefs.edit().putBoolean("pairing_just_succeeded", false).apply();
            btnAdbAction.setText("Securing connection...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> adbShareController.startAdbPairingFlow(true), 2500);
        }
        if (adbPrefs.getBoolean("focus_adb", false)) {
            adbPrefs.edit().putBoolean("focus_adb", false).apply();
            new Handler(Looper.getMainLooper()).postDelayed(this::focusAdvancedMonitoring, 600);
        }
    }


    public void updateDynamicButtons() {
        MainActivity mainAct = (MainActivity) getActivity();
        if (mainAct == null || !isAdded()) return;

        boolean isServerRunning = mainAct.isServerAlive;
        final File iiabRootDir = new File(requireContext().getFilesDir(), "rootfs");
        final File debianRootfs = new File(iiabRootDir, "installed-rootfs/iiab");
        final File backupsDir = new File(iiabRootDir, "backups");
        if (!backupsDir.exists()) backupsDir.mkdirs();

        boolean isProotInstalled = new File(debianRootfs, "etc/os-release").exists() || new File(debianRootfs, "usr/bin/bash").exists();
        refreshDashboardLeds(mainAct);

        // Animated Banner
        View bannerWarning = getView().findViewById(R.id.banner_server_warning);
        if (bannerWarning != null) {
            boolean isBannerVisible = bannerWarning.getVisibility() == View.VISIBLE;
            if (isServerRunning && !isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.VISIBLE);
            } else if (!isServerRunning && isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.GONE);
            }
        }

        // Refresh Button
        if (btnRefreshModules != null) {
            btnRefreshModules.setEnabled(true);
            if (isServerRunning || !isProotInstalled) {
                btnRefreshModules.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_disabled));
                btnRefreshModules.setAlpha(0.6f);
                btnRefreshModules.setOnClickListener(v -> {
                    if (!isProotInstalled)
                        Snackbar.make(v, R.string.install_msg_termux_missing, Snackbar.LENGTH_LONG).show();
                    else if (isServerRunning)
                        Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                });
            } else {
                btnRefreshModules.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_info));
                btnRefreshModules.setAlpha(1.0f);
                btnRefreshModules.setOnClickListener(v -> {
                    v.setAlpha(0.5f);
                    requestFreshLocalVars();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> v.setAlpha(1.0f), 1000);
                });
            }
        }

        // Basic Enablers
        btnFastInstall.setEnabled(true);
        btnFastDelete.setEnabled(true);
        if (btnAdvancedReset != null) btnAdvancedReset.setEnabled(true);
        if (btnAdvancedBackup != null) btnAdvancedBackup.setEnabled(true);
        if (btnAdvancedRestore != null) btnAdvancedRestore.setEnabled(true);
        if (txtSelectBackupTitle != null) txtSelectBackupTitle.setEnabled(true);

        if (btnAdvancedForceStop != null) {
            btnAdvancedForceStop.setEnabled(true);
            btnAdvancedForceStop.setAlpha(1.0f);
            btnAdvancedForceStop.setOnClickListener(v -> openTermuxAppInfo());
        }

        boolean isBusy = isSystemBusy();

        // 1. ALWAYS link the buttons so Listeners can intercept and drop the Snackbar
        installController.bind(mainAct, debianRootfs, iiabRootDir,
                btnFastInstall, btnLaunchInstall, discrepancyWarning, rolesContainer, chkCompanionData);
        resetDeleteController.bind(mainAct, debianRootfs, btnAdvancedReset, btnFastDelete);
        backupController.bind(mainAct, backupsDir, iiabRootDir,
                btnImportBackup, btnAdvancedBackup, btnAdvancedRestore,
                txtSelectBackupTitle, txtBackupStatus, containerBackupList,
                restoreLogPanel, restoreLogText, restoreLogResult, restoreLogScroll);

        if (isServerRunning || isBusy) {
            // LOCK MODE: Server On or System Busy
            float lockAlpha = 0.5f;

            // We keep the opacity at 80% only for the button that is currently working
            btnFastInstall.setAlpha((isDownloadingRootfs() && !isServerRunning) ? 0.8f : lockAlpha);
            btnFastDelete.setAlpha(isDeleting ? 0.8f : lockAlpha);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(isBackupInProgress ? 0.8f : lockAlpha);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setAlpha(isRestoring ? 0.8f : lockAlpha);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha((isDownloadingRootfs() && !isServerRunning) ? 0.8f : lockAlpha);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(lockAlpha);
            if (btnImportBackup != null) btnImportBackup.setAlpha(isImporting ? 0.8f : lockAlpha);

            // Lock module checkboxes in the grid
            for (CheckBox cb : newInstallCheckboxes) {
                cb.setEnabled(false);
                View card = (View) cb.getParent().getParent();
                card.setAlpha(0.6f);
                card.setOnClickListener(v -> {
                    String msg = isServerRunning ? getString(R.string.install_msg_server_running_lock) : getSystemBusyMessage();
                    Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show();
                });
            }

        } else {
            // FREE MODE: All off and ready to operate
            if (!hasInternet || getSelectedTier() == null || !isStorageSafe) btnFastInstall.setAlpha(0.4f);
            else btnFastInstall.setAlpha(1.0f);

            btnFastDelete.setAlpha(1.0f);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(1.0f);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha(1.0f);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(1.0f);
            if (btnImportBackup != null) btnImportBackup.setAlpha(1.0f);

            btnFastInstall.setEnabled(true);
            btnFastInstall.setTextSize(14f);
            if (!hasInternet) {
                // Offline: downloading is impossible. Signal it on the button itself;
                // the click listener shows a snackbar instead of starting a failing download.
                btnFastInstall.setText(R.string.install_btn_no_connection);
            } else {
                btnFastInstall.setText(isProotInstalled ? R.string.install_btn_reinstall : R.string.install_btn_install);
            }

            // Unlock checkboxes
            for (CheckBox cb : newInstallCheckboxes) {
                cb.setEnabled(true);
                View card = (View) cb.getParent().getParent();
                card.setAlpha(1.0f);
                card.setOnClickListener(v -> cb.toggle());
            }
        }
    }


    // =========================================================================================
    // REGION 4: INSTALLATION PLANNER
    // =========================================================================================



    /**
     * Completes the storage projection once {@link RootfsViewModel} resolves the OS
     * size (live, or the offline fallback). The UI now consumes the size from the
     * presentation layer instead of having {@link InstallationPlanner} resolve it.
     */



    // =========================================================================================
    // REGION 5: NATIVE PIPELINES
    // =========================================================================================














    // =========================================================================================
    // REGION 6: BACKUP & RESTORE SAF
    // =========================================================================================



    // =========================================================================================
    // REGION 7: ADB & SYSTEM RESTRICTIONS
    // =========================================================================================







    private int getDynamicAdbPort(int fallbackPort) {
        try {
            Process process = Runtime.getRuntime().exec("getprop service.adb.tls.port");
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String portStr = reader.readLine();
            reader.close();
            if (portStr != null && !portStr.trim().isEmpty())
                return Integer.parseInt(portStr.trim());
        } catch (Exception ignored) {
        }
        return fallbackPort;
    }









    private void setupCpuChart() {
        cpuChart.getDescription().setEnabled(false);
        cpuChart.setTouchEnabled(false);
        cpuChart.setDrawGridBackground(false);
        cpuChart.getLegend().setEnabled(false);

        XAxis xAxis = cpuChart.getXAxis();
        xAxis.setDrawLabels(false);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(ContextCompat.getColor(requireContext(), R.color.divider_line));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        YAxis leftAxis = cpuChart.getAxisLeft();
        leftAxis.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
        leftAxis.setAxisMaximum(100f);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(ContextCompat.getColor(requireContext(), R.color.divider_line));

        cpuChart.getAxisRight().setEnabled(false);
        cpuChart.setData(new LineData());
    }

    public void addCpuEntry(float cpuPercentage) {
        if (cpuChart == null || cpuChart.getData() == null) return;
        LineData data = cpuChart.getData();
        ILineDataSet set = data.getDataSetByIndex(0);

        if (set == null) {
            LineDataSet newSet = new LineDataSet(null, "CPU");
            newSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            newSet.setColor(ContextCompat.getColor(requireContext(), R.color.accent));
            newSet.setLineWidth(2f);
            newSet.setDrawCircles(false);
            newSet.setDrawValues(false);
            newSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            newSet.setDrawFilled(true);
            newSet.setFillColor(ContextCompat.getColor(requireContext(), R.color.accent));
            newSet.setFillAlpha(50);
            set = newSet;
            data.addDataSet(set);
        }

        data.addEntry(new Entry(set.getEntryCount(), cpuPercentage), 0);
        data.notifyDataChanged();
        cpuChart.notifyDataSetChanged();
        cpuChart.setVisibleXRangeMaximum(60);
        cpuChart.moveViewToX(data.getEntryCount());
    }


    // =========================================================================================
    // REGION 8: UTILITIES
    // =========================================================================================

    public boolean isSystemBusy() {
        return isDownloadingRootfs() || isBatchInstalling || isBackupInProgress || isRestoring || isDeleting || isImporting;
    }

    public String getSystemBusyMessage() {
        if (isDownloadingRootfs()) return getString(R.string.install_busy_provisioning);
        if (isBatchInstalling) return getString(R.string.install_busy_modules);
        if (isBackupInProgress) return getString(R.string.install_busy_backup);
        if (isRestoring) return getString(R.string.install_busy_restore);
        if (isDeleting) return getString(R.string.install_busy_delete);
        if (isImporting) return getString(R.string.install_busy_import);
        return getString(R.string.install_busy_generic);
    }

    public void enableSystemProtection() {
        // SAFE CHECK
        if (!isAdded() || getContext() == null) return;

        try {
            Intent intent = new Intent(requireContext(), WatchdogService.class);
            intent.setAction(WatchdogService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent);
            } else {
                requireContext().startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    public void disableSystemProtection() {
        // SAFE CHECK
        if (!isAdded() || getContext() == null) return;

        try {
            Intent intent = new Intent(requireContext(), WatchdogService.class);
//            intent.setAction(WatchdogService.ACTION_START);
            intent.setAction(WatchdogService.ACTION_STOP);
            requireContext().startService(intent);
        } catch (Exception ignored) {
        }
    }


    private void restoreQueueFromPrefs() {
        if (getActivity() == null) return;
        android.content.SharedPreferences prefs = getActivity().getSharedPreferences("iiab_queue_prefs", android.content.Context.MODE_PRIVATE);
        isBatchInstalling = prefs.getBoolean("is_batch_installing", false);
        String queueString = prefs.getString("pending_modules", "");
        installationQueue.clear();
        if (!queueString.isEmpty()) {
            String[] modules = queueString.split(",");
            installationQueue.addAll(java.util.Arrays.asList(modules));
        }
    }

    public boolean pingUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("HEAD");
            int responseCode = conn.getResponseCode();
            return (responseCode >= 200 && responseCode < 400);
        } catch (Exception e) {
            return false;
        }
    }


    public String getTermuxArch() {
        try {
            android.content.pm.ApplicationInfo info = requireContext().getApplicationInfo();
            String nativeLibDir = info.nativeLibraryDir;
            if (nativeLibDir != null) {
                if (nativeLibDir.endsWith("arm64") || nativeLibDir.contains("arm64-v8a"))
                    return "arm64-v8a";
                if (nativeLibDir.endsWith("arm") || nativeLibDir.contains("armeabi-v7a"))
                    return "armeabi-v7a";
                if (nativeLibDir.endsWith("x86_64") || nativeLibDir.contains("x86_64"))
                    return "x86_64";
                if (nativeLibDir.endsWith("x86") || nativeLibDir.contains("x86")) return "x86";
            }
        } catch (Exception ignored) {
        }
        if (android.os.Build.SUPPORTED_ABIS.length > 0) return android.os.Build.SUPPORTED_ABIS[0];
        return "unknown";
    }

    /** Maps the legacy planner tier to the domain {@link RootfsTier}. */

    /** Detects the device ABI for rootfs selection, reusing {@link #getTermuxArch()}. */

    public void openTermuxAppInfo() {
        try {
            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            android.net.Uri uri = android.net.Uri.fromParts("package", requireContext().getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void checkInternetAccess() {
        new Thread(() -> {
            boolean hasInternet = false;
            try {
                URL url = new URL("https://clients3.google.com/generate_204");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setUseCaches(false);
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("HEAD");
                hasInternet = (conn.getResponseCode() == 204 || conn.getResponseCode() == 200);
            } catch (Exception e) {
                hasInternet = false;
            }
            final boolean isOnline = hasInternet;
            DeployFragment.this.hasInternet = isOnline;
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (ledInternet != null) {
                        if (isOnline) {
                            ledInternet.setBackgroundResource(R.drawable.led_on_green);
                            ledInternet.setBackgroundTintList(null);
                        } else {
                            ledInternet.setBackgroundResource(R.drawable.led_off);
                            ledInternet.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_danger)));
                        }
                    }
                });
            }
        }).start();
    }

    private void loadLocalVarsFallback() {
        File jsonFile = new File(sharedStateDir, "local_vars.json");
        if (jsonFile.exists() && jsonFile.length() > 0) {
            try {
                StringBuilder text = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                String line;
                while ((line = br.readLine()) != null) text.append(line);
                br.close();
                lastKnownState = new JSONObject(text.toString());
                installController.verifyInstallationState(lastKnownState);
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshDashboardLeds(MainActivity mainAct) {
        if (mainAct == null) return;
        boolean isDevModeOn = false;
        try {
            isDevModeOn = android.provider.Settings.Global.getInt(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        } catch (Exception ignored) {
        }
        if (ledDevMode != null)
            ledDevMode.setBackgroundResource(isDevModeOn ? R.drawable.led_on_green : R.drawable.led_off);
    }

    public float parseCpuUsage(String cpuLine) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+)%cpu.*?(\\d+)%idle");
            java.util.regex.Matcher m = p.matcher(cpuLine.toLowerCase());
            if (m.find()) {
                float totalCpu = Float.parseFloat(m.group(1));
                float idleCpu = Float.parseFloat(m.group(2));
                if (totalCpu > 0) return ((totalCpu - idleCpu) / totalCpu) * 100f;
            }
        } catch (Exception ignored) {
        }
        return -1f;
    }

    private void requestFreshLocalVars() {
        installController.fetchLocalVarsFromPRoot();
    }

    public void requestFreshLocalVarsSilently() {
        installController.fetchLocalVarsFromPRoot();
    }

    // --- BackupHost seam (backup/restore logic lives in BackupController) ---
    @Override public void setImporting(boolean importing) { this.isImporting = importing; }
    @Override public void setRestoring(boolean restoring) { this.isRestoring = restoring; }
    @Override public void setBackupInProgress(boolean inProgress) { this.isBackupInProgress = inProgress; }
    @Override public boolean isBackupInProgress() { return this.isBackupInProgress; }

    // --- PlannerHost seam (planner logic lives in PlannerController) ---
    @Override public InstallationPlanner.Tier getSelectedTier() { return downloadState.getSelectedTier(); }
    @Override public java.util.Set<String> selectedModuleKeys() { return downloadState.getSelectedModuleKeys(); }
    @Override public void setSelectedTier(InstallationPlanner.Tier tier) { downloadState.setSelectedTier(tier); }
    @Override public boolean isCompanionData() { return downloadState.isCompanionData(); }
    @Override public void setCompanionData(boolean v) { downloadState.setCompanionData(v); }
    @Override public java.util.List<android.widget.CheckBox> moduleCheckboxes() { return newInstallCheckboxes; }
    @Override public void setStorageSafe(boolean safe) { this.isStorageSafe = safe; }
    @Override public boolean isStorageSafe() { return this.isStorageSafe; }
    @Override public String getOverrideKiwixLang() { return downloadState.getOverrideKiwixLang(); }
    @Override public void setOverrideKiwixLang(String lang) { downloadState.setOverrideKiwixLang(lang); }
    @Override public String getOverrideKiwixVariant() { return downloadState.getOverrideKiwixVariant(); }
    @Override public void setOverrideKiwixVariant(String variant) { downloadState.setOverrideKiwixVariant(variant); }
    @Override public boolean hasInternet() { return this.hasInternet; }

    // --- InstallHost seam (install pipeline lives in InstallController) ---
    // ADFA-4474 PR2: InstallProgressRepository is the single source of truth for
    // "an install is in flight" (survives recreation; the InstallService is the writer).
    @Override public boolean isDownloadingRootfs() { return org.iiab.controller.install.presentation.InstallProgressRepository.get().isRunning(); }
    @Override public void setDownloadingRootfs(boolean v) { /* no-op: derived from the repository now */ }
    @Override public boolean isBatchInstalling() { return isBatchInstalling; }
    @Override public void setBatchInstalling(boolean v) { isBatchInstalling = v; }
    @Override public java.util.List<String> installationQueue() { return installationQueue; }
    @Override public org.json.JSONObject getLastKnownState() { return lastKnownState; }
    @Override public void setLastKnownState(org.json.JSONObject v) { lastKnownState = v; }
    @Override public org.iiab.controller.Aria2Manager aria2Manager() { return downloadState.getAria2Manager(); }
    @Override public void setAria2Manager(org.iiab.controller.Aria2Manager v) { downloadState.setAria2Manager(v); }
    @Override public org.iiab.controller.PRootEngine prootEngine() { return prootEngine; }
    @Override public void setPRootEngine(org.iiab.controller.PRootEngine v) { prootEngine = v; }

    // --- ResetDeleteHost seam ---
    @Override public boolean isDeleting() { return isDeleting; }
    @Override public void setDeleting(boolean v) { isDeleting = v; }
}
