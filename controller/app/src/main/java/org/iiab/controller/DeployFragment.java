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
        org.iiab.controller.install.presentation.InstallHost {

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
    private String overrideKiwixLang = null;
    private String overrideKiwixVariant = null;
    private InstallationPlanner.Tier selectedTier = null;
    // Presentation-layer source of the OS rootfs size (live, with offline fallback).
    // Last known connectivity, refreshed by checkInternetAccess() (every 3s via liveStatusRunnable).
    private volatile boolean hasInternet = true;

    // Native Engine Variables
    private static Aria2Manager aria2Manager;
    private static boolean isDownloadingRootfs = false;
    // State Variables (New control variables)
    private boolean isRestoring = false;
    private boolean isDeleting = false;
    private boolean isImporting = false;
    private PRootEngine prootEngine;

    // Background Handlers
    private final Handler liveStatusHandler = new Handler(Looper.getMainLooper());
    private Runnable liveStatusRunnable;

    // ADB Variables
    private boolean isConnectedToAdb = false, isScanning = false, isAttemptingFastConnect = false;
    private android.net.nsd.NsdManager nsdManager;
    private String discoveredHostIp = "127.0.0.1";
    private int discoveredConnectPort = -1, discoveredPairingPort = -1;
    private android.net.nsd.NsdManager.DiscoveryListener connectDiscoveryListener, pairingDiscoveryListener;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    private static final String CHANNEL_ID = "adb_pairing_channel";
    private static final String SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp.";
    private static final String SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp.";

    private final BroadcastReceiver adbUiUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if ("org.iiab.controller.ADB_PAIRING_SUCCESSFUL".equals(action)) {
                requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("pairing_just_succeeded", false).apply();

                android.util.Log.i(TAG, "Broadcast received: Pairing successful! Re-scanning in 2.5s...");
                if (isAdded()) {
                    btnAdbAction.setText(getString(R.string.adb_status_securing));
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startAdbPairingFlow();
                    }, 2500);
                }
            } else if ("org.iiab.controller.ADB_PAIRING_FAILED".equals(action)) {
                android.util.Log.w(TAG, "Broadcast received: Pairing failed.");
                if (isAdded()) resetScanState();

            } else if ("org.iiab.controller.ADB_PAIRING_SENT".equals(action)) {
                btnAdbAction.setText(getString(R.string.adb_status_connected));
                isConnectedToAdb = true;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isAdded()) updateUiState(true);
                }, 500);

            } else if ("org.iiab.controller.ADB_CPU_UPDATE".equals(action)) {
                String cpuData = intent.getStringExtra("cpu_line");
                if (isAdded() && isConnectedToAdb && cpuData != null) {
                    float cpuVal = parseCpuUsage(cpuData);
                    if (cpuVal >= 0f) {
                        addCpuEntry(cpuVal);
                    }
                }
            } else if ("org.iiab.controller.ADB_RESTRICTIONS_UPDATE".equals(action)) {
                if (!isAdded()) return;

                String cpValue = intent.getStringExtra("child_process_value");
                String rawPpkValue = intent.getStringExtra("ppk_value");

                requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("child_process_value", cpValue)
                        .putString("ppk_value", rawPpkValue)
                        .apply();

                String ppkDisplay = ("null".equals(rawPpkValue) || "unknown".equals(rawPpkValue)) ? getString(R.string.adb_ppk_default) : rawPpkValue;

                ledDcpr.setBackgroundTintList(null);
                ledPpk.setBackgroundTintList(null);
                ledPpk.setBackgroundResource(R.drawable.led_off);
                ledDcpr.setBackgroundResource(R.drawable.led_off);

                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_not_required, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));
                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green);
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_pending)));
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_info)));
                    }

                    if ("0".equals(cpValue) || "false".equals(cpValue)) {
                        ledDcpr.setBackgroundResource(R.drawable.led_on_green);
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_disabled_ok), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else if ("1".equals(cpValue) || "true".equals(cpValue) || "null".equals(cpValue) || cpValue == null) {
                        ledDcpr.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_danger)));
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_enabled_limiting), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else {
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_unknown), android.text.Html.FROM_HTML_MODE_COMPACT));
                    }

                } else if (android.os.Build.VERSION.SDK_INT >= 31) {
                    txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_not_required), android.text.Html.FROM_HTML_MODE_COMPACT));
                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_active, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));

                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green);
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_danger)));
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_pending)));
                    }
                }
            }
        }
    };


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

        nsdManager = (android.net.nsd.NsdManager) requireContext().getSystemService(Context.NSD_SERVICE);
        sharedStateDir = new File(Environment.getExternalStorageDirectory(), ".iiab_state");

        // Initialization Logic
        backupController.registerLaunchers();
        setupAdbNetworking();
        setupAdvancedMonitoringMenu(view);
        setupCpuChart();
        setupAdbListeners();
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

        registerAdbReceiver();
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
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            requireContext().unregisterReceiver(adbUiUpdateReceiver);
        } catch (Exception ignored) {
        }
        liveStatusHandler.removeCallbacks(liveStatusRunnable);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (getActivity() != null && getActivity().isChangingConfigurations()) return;
        if (aria2Manager != null && isDownloadingRootfs) {
            aria2Manager.stopDownload();
            isDownloadingRootfs = false;
        }
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> startAdbPairingFlow(true), 2500);
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
        bindDeleteButtonLogic(mainAct, debianRootfs);
        backupController.bind(mainAct, backupsDir, iiabRootDir,
                btnImportBackup, btnAdvancedBackup, btnAdvancedRestore,
                txtSelectBackupTitle, txtBackupStatus, containerBackupList,
                restoreLogPanel, restoreLogText, restoreLogResult, restoreLogScroll);
        bindResetButtonLogic(mainAct, debianRootfs);

        if (isServerRunning || isBusy) {
            // LOCK MODE: Server On or System Busy
            float lockAlpha = 0.5f;

            // We keep the opacity at 80% only for the button that is currently working
            btnFastInstall.setAlpha((isDownloadingRootfs && !isServerRunning) ? 0.8f : lockAlpha);
            btnFastDelete.setAlpha(isDeleting ? 0.8f : lockAlpha);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(isBackupInProgress ? 0.8f : lockAlpha);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setAlpha(isRestoring ? 0.8f : lockAlpha);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha((isDownloadingRootfs && !isServerRunning) ? 0.8f : lockAlpha);
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
            if (!hasInternet || selectedTier == null || !isStorageSafe) btnFastInstall.setAlpha(0.4f);
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


    private void bindDeleteButtonLogic(MainActivity mainAct, File debianRootfs) {
        btnFastDelete.setOnClickListener(v -> {
            if (mainAct.isServerAlive) {
                Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (isSystemBusy()) {
                Snackbar.make(v, getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                return;
            }

            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.install_dialog_delete_title)
                    .setMessage(R.string.install_dialog_delete_msg)
                    .setPositiveButton(R.string.install_btn_delete_confirm, (dialog, which) -> {
                        isDeleting = true;
                        mainAct.runOnUiThread(this::updateDynamicButtons);

                        mainAct.invalidateModuleStateTrust();
                        btnFastDelete.setEnabled(false);
                        btnFastDelete.startProgress();
                        Snackbar.make(getView(), R.string.install_status_deleting, Snackbar.LENGTH_SHORT).show();
                        new Thread(() -> {
                            enableSystemProtection();
                            try {
                                ProcessRunner.Result wipeResult = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                if (!wipeResult.isSuccess()) {
                                    Log.w(TAG, "rm -rf rootfs (delete) failed (exit " + wipeResult.exitCode + "): " + wipeResult.output);
                                }
                            } catch (Exception e) {
                                mainAct.runOnUiThread(() -> Snackbar.make(getView(), getString(R.string.install_error_delete, e.getMessage()), Snackbar.LENGTH_LONG).show());
                            } finally {
                                isDeleting = false;
                                mainAct.runOnUiThread(() -> { btnFastDelete.stopProgress(); updateDynamicButtons(); });
                                disableSystemProtection();
                            }
                        }).start();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void bindResetButtonLogic(MainActivity mainAct, File debianRootfs) {
        if (btnAdvancedReset == null) return;
        btnAdvancedReset.setOnClickListener(v -> {

            if (mainAct.isServerAlive) {
                Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (isSystemBusy()) {
                Snackbar.make(v, getSystemBusyMessage(), Snackbar.LENGTH_LONG).show();
                return;
            }
            // NORMAL STATE: RESET START
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.install_dialog_reset_title)
                    .setMessage(R.string.install_dialog_reset_msg)
                    .setPositiveButton(R.string.install_dialog_reset_confirm, (dialog, which) -> {
                        // IMMEDIATE LOCK AGAINST DOUBLE PRESS
                        if (isDownloadingRootfs) return;
                        isDownloadingRootfs = true;

                        mainAct.invalidateModuleStateTrust();
                        final String originalText = getString(R.string.install_btn_reset);

                        // We enable the button but change the text to serve as "Cancel"
                        btnAdvancedReset.setEnabled(true);

                        new Thread(() -> {
                            enableSystemProtection();
                            try {
                                mainAct.runOnUiThread(() -> {
                                    btnAdvancedReset.setText(getString(R.string.install_status_wiping_old));
                                    Snackbar.make(getView(), R.string.install_status_starting_vanilla, Snackbar.LENGTH_SHORT).show();
                                });

                                // 1. WIPE
                                ProcessRunner.Result wipeResult = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                if (!wipeResult.isSuccess()) {
                                    Log.w(TAG, "rm -rf rootfs (vanilla reset) failed (exit " + wipeResult.exitCode + "): " + wipeResult.output);
                                }
                                debianRootfs.mkdirs();

                                // 2. DOWNLOAD
                                mainAct.runOnUiThread(() -> btnAdvancedReset.setText(getString(R.string.install_status_downloading_debian)));
                                if (aria2Manager == null) aria2Manager = new Aria2Manager();

                                String arch = getTermuxArch();
                                String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "arm" : "aarch64";
                                String tarball = "debian-trixie-" + archSuffix + "-pd-v4.29.0.tar.xz";
                                String url = "https://iiab.switnet.org/android/rootfs/proot-distro-v4.29.0/" + tarball;

                                aria2Manager.startDownload(requireContext(), url, new Aria2Manager.DownloadListener() {
                                    @Override
                                    public void onProgress(int percentage, String speed, String eta) {
                                        if (isAdded() && getActivity() != null) {
                                            getActivity().runOnUiThread(() -> {
                                                // We keep it visible that you can cancel
                                                btnAdvancedReset.setText(getString(R.string.install_status_debian_download, percentage, speed) + " (Tap to Cancel)");
                                            });
                                        }
                                    }

                                    @Override
                                    public void onComplete(String downloadPath) {
                                        new Thread(() -> {
                                            try {
                                                // 3. EXTRACT
                                                isDownloadingRootfs = false;
                                                mainAct.runOnUiThread(() -> {
                                                    btnAdvancedReset.setText(getString(R.string.install_status_extracting_base));
                                                    btnAdvancedReset.setEnabled(false); // <--- SAFE DOOR CLOSURE
                                                });

                                                File downloadedArchive = new File(downloadPath, tarball);
                                                File staticTar = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libtar.so");
                                                File staticXz = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libxz.so");
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
                                                mainAct.runOnUiThread(() -> btnAdvancedReset.setText(getString(R.string.install_status_bootstrapping)));

                                                // DNS is written at the chokepoint (PRootEngine) before the
                                                // bootstrap proot run below; no inline write needed here.

                                                if (prootEngine == null)
                                                    prootEngine = new PRootEngine();
                                                String bootstrapCmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                                                        "export DEBIAN_FRONTEND=noninteractive && " +
                                                        "apt-get update && apt-get install -y curl ca-certificates nano sudo && " +
                                                        "curl -fsSL https://raw.githubusercontent.com/appdevforall/KnowledgeToGo/main/iiab-android -o /usr/local/sbin/iiab-android && " +
                                                        "chmod +x /usr/local/sbin/iiab-android && " +
                                                        "apt-get clean && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* /tmp/* /root/.cache";

                                                prootEngine.executeInContainer(requireContext(), debianRootfs.getAbsolutePath(), "/bin/bash -c '" + bootstrapCmd + "'", new PRootEngine.OutputListener() {
                                                    @Override
                                                    public void onOutputLine(String line) {
                                                        mainAct.runOnUiThread(() -> mainAct.addToLog("[Bootstrap] " + line));
                                                    }

                                                    @Override
                                                    public void onProcessExit(int exitCode) {
                                                        mainAct.runOnUiThread(() -> {
                                                            disableSystemProtection();
                                                            btnAdvancedReset.setText(originalText);
                                                            btnAdvancedReset.setEnabled(true);
                                                            updateDynamicButtons();
                                                            Snackbar.make(getView(), R.string.install_success_vanilla, Snackbar.LENGTH_LONG).show();
                                                        });
                                                    }

                                                    @Override
                                                    public void onError(String error) {
                                                        mainAct.runOnUiThread(() -> {
                                                            disableSystemProtection();
                                                            btnAdvancedReset.setText(originalText);
                                                            btnAdvancedReset.setEnabled(true);
                                                            updateDynamicButtons();
                                                            Snackbar.make(getView(), getString(R.string.install_error_bootstrap, error), Snackbar.LENGTH_LONG).show();
                                                        });
                                                    }
                                                });

                                            } catch (Exception e) {
                                                mainAct.runOnUiThread(() -> {
                                                    isDownloadingRootfs = false;
                                                    disableSystemProtection();
                                                    btnAdvancedReset.setText(originalText);
                                                    btnAdvancedReset.setEnabled(true);
                                                    updateDynamicButtons();
                                                    Snackbar.make(getView(), getString(R.string.install_error_extract_bootstrap, e.getMessage()), Snackbar.LENGTH_LONG).show();
                                                });
                                            }
                                        }).start();
                                    }

                                    @Override
                                    public void onError(String error) {
                                        mainAct.runOnUiThread(() -> {
                                            isDownloadingRootfs = false;
                                            disableSystemProtection();
                                            btnAdvancedReset.setText(originalText);
                                            btnAdvancedReset.setEnabled(true);
                                            updateDynamicButtons();
                                            Snackbar.make(getView(), getString(R.string.install_error_download, error), Snackbar.LENGTH_LONG).show();
                                        });
                                    }
                                });

                            } catch (Exception e) {
                                mainAct.runOnUiThread(() -> {
                                    isDownloadingRootfs = false;
                                    disableSystemProtection();
                                    btnAdvancedReset.setText(originalText);
                                    btnAdvancedReset.setEnabled(true);
                                    updateDynamicButtons();
                                    Snackbar.make(getView(), getString(R.string.install_error_reset, e.getMessage()), Snackbar.LENGTH_LONG).show();
                                });
                            }
                        }).start();
                    })
                    .setNegativeButton(R.string.install_dialog_reset_cancel, null)
                    .show();
        });
    }











    // =========================================================================================
    // REGION 6: BACKUP & RESTORE SAF
    // =========================================================================================



    // =========================================================================================
    // REGION 7: ADB & SYSTEM RESTRICTIONS
    // =========================================================================================

    private void setupAdbNetworking() {
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("iiab_adb_multicast_lock");
            multicastLock.setReferenceCounted(true);
        }
    }

    private void setupAdbListeners() {
        LinearLayout containerDcpr = getView().findViewById(R.id.container_led_dcpr);
        containerDcpr.setOnClickListener(v -> {
            if (!isConnectedToAdb) {
                Snackbar.make(v, R.string.adb_req_cp, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (android.os.Build.VERSION.SDK_INT < 34) {
                Snackbar.make(v, R.string.adb_not_req_cp, Snackbar.LENGTH_LONG).show();
                return;
            }

            IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
            adbManager.executeCommand("settings put global settings_enable_monitor_phantom_procs 0");
            Snackbar.make(v, R.string.adb_snack_disabling_cp, Snackbar.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
        });

        LinearLayout containerPpk = getView().findViewById(R.id.container_led_ppk);
        containerPpk.setOnClickListener(v -> {
            if (!isConnectedToAdb) {
                Snackbar.make(v, R.string.adb_req_ppk, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (android.os.Build.VERSION.SDK_INT < 31) {
                Snackbar.make(v, R.string.adb_not_req_ppk, Snackbar.LENGTH_LONG).show();
                return;
            }

            IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
            adbManager.executeCommand("device_config put activity_manager max_phantom_processes 256");
            Snackbar.make(v, R.string.adb_snack_setting_ppk, Snackbar.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
        });

        btnAdbAction.setOnClickListener(v -> {
            if (isConnectedToAdb) {
                new Thread(() -> {
                    try {
                        IIABAdbManager.getInstance(requireContext()).disconnect();
                    } catch (Exception ignored) {
                    }
                }).start();
                isConnectedToAdb = false;
                updateUiState(false);
            } else if (!isScanning) {
                startAdbPairingFlow();
            }
        });
    }

    private void registerAdbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("org.iiab.controller.ADB_PAIRING_SUCCESSFUL");
        filter.addAction("org.iiab.controller.ADB_PAIRING_FAILED");
        filter.addAction("org.iiab.controller.ADB_PAIRING_SENT");
        filter.addAction("org.iiab.controller.ADB_CPU_UPDATE");
        filter.addAction("org.iiab.controller.ADB_RESTRICTIONS_UPDATE");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(adbUiUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(adbUiUpdateReceiver, filter);
        }
    }

    private void updateUiState(boolean isConnected) {
        btnAdbAction.setEnabled(true);
        if (isConnected) {
            ledAdbStatus.setBackgroundResource(R.drawable.led_on_green);
            txtAdbLedLabel.setText(getString(R.string.adb_status_connected));
            txtAdbLedLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_success));
            btnAdbAction.setText(getString(R.string.adb_btn_disconnect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_danger)));

            txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_ui_checking_cp), android.text.Html.FROM_HTML_MODE_COMPACT));
            txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ui_checking_ppk), android.text.Html.FROM_HTML_MODE_COMPACT));
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);
        } else {
            ledAdbStatus.setBackgroundResource(R.drawable.led_off);
            txtAdbLedLabel.setText(getString(R.string.adb_status_offline));
            txtAdbLedLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
            btnAdbAction.setText(getString(R.string.adb_btn_connect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_info)));

            txtDcpr.setText(getString(R.string.adb_ui_unknown_cp));
            txtPpk.setText(getString(R.string.adb_ui_unknown_ppk));
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);
        }
    }

    private void startAdbPairingFlow() {
        startAdbPairingFlow(false);
    }

    private void startAdbPairingFlow(boolean isSilentScan) {
        isScanning = true;
        isAttemptingFastConnect = false;
        btnAdbAction.setText(getString(R.string.adb_btn_scanning));
        btnAdbAction.setEnabled(false);
        discoveredConnectPort = -1;
        discoveredPairingPort = -1;

        if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();

        connectDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_CONNECT);
        pairingDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_PAIRING);

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
        } catch (Exception e) {
            resetScanState();
            return;
        }

        if (!isSilentScan) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isScanning && !isConnectedToAdb) openDeveloperOptions();
            }, 4000);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::checkIfScanTimedOut, 90000);
    }

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

    private void attemptFastConnection(String hostIp, int port) {
        if (isAttemptingFastConnect) return;
        isAttemptingFastConnect = true;

        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            boolean connected = false;
            IIABAdbManager adbManager = IIABAdbManager.getInstance(appContext);

            for (int i = 0; i < 6; i++) {
                try {
                    adbManager.connect(hostIp, port);
                    connected = true;
                    break;
                } catch (Exception e) {
                    try {
                        adbManager.disconnect();
                        Thread.sleep(600);
                    } catch (Exception ignored) {
                    }
                }
            }

            if (connected) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopDiscovery();
                    isConnectedToAdb = true;
                    if (btnAdbAction != null)
                        btnAdbAction.setText(getString(R.string.adb_status_connected));
                    updateUiState(true);
                });
                adbManager.startCpuMonitor(appContext);
                adbManager.checkSystemRestrictions(appContext);
            } else {
                isAttemptingFastConnect = false;
            }
        }).start();
    }

    private void openDeveloperOptions() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Snackbar.make(getView(), R.string.adb_snack_dev_options, Snackbar.LENGTH_LONG).show();
        }
    }

    private android.net.nsd.NsdManager.DiscoveryListener createDiscoveryListener(String serviceType) {
        return new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
            }

            @Override
            public void onServiceLost(android.net.nsd.NsdServiceInfo service) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onServiceFound(android.net.nsd.NsdServiceInfo service) {
                if (service.getServiceType().contains("_adb-tls")) resolveService(service);
            }
        };
    }

    private void resolveService(android.net.nsd.NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new android.net.nsd.NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(android.net.nsd.NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onServiceResolved(android.net.nsd.NsdServiceInfo serviceInfo) {
                int port = serviceInfo.getPort();
                String type = serviceInfo.getServiceType();
                String hostIp = serviceInfo.getHost().getHostAddress();
                String myIp = getLocalWifiIp();

                if (hostIp != null && !hostIp.equals(myIp) && !hostIp.equals("127.0.0.1")) return;

                new Handler(Looper.getMainLooper()).post(() -> {
                    discoveredHostIp = hostIp;
                    if (type.contains("connect")) {
                        discoveredConnectPort = port;
                        attemptFastConnection(hostIp, port);
                    } else if (type.contains("pairing")) {
                        discoveredPairingPort = port;
                    }

                    if (discoveredConnectPort != -1 && discoveredPairingPort != -1 && !isConnectedToAdb) {
                        stopDiscovery();
                        showPairingNotification(discoveredHostIp, discoveredConnectPort, discoveredPairingPort);
                        resetScanState();
                    }
                });
            }
        });
    }

    private void showPairingNotification(String hostIp, int connectPort, int pairingPort) {
        RemoteInput remoteInput = new RemoteInput.Builder(AdbPairingReceiver.KEY_PIN_REPLY).setLabel(getString(R.string.adb_notif_input_hint)).build();
        Intent replyIntent = new Intent(requireContext(), AdbPairingReceiver.class);
        replyIntent.putExtra("hostIp", hostIp);
        replyIntent.putExtra("connectPort", connectPort);
        replyIntent.putExtra("pairingPort", pairingPort);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent replyPendingIntent = PendingIntent.getBroadcast(requireContext(), 0, replyIntent, flags);
        NotificationCompat.Action action = new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, getString(R.string.adb_notif_action_pin), replyPendingIntent).addRemoteInput(remoteInput).build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "ADB Pairing", NotificationManager.IMPORTANCE_HIGH);
            requireContext().getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.adb_notif_title))
                .setContentText(getString(R.string.adb_notif_desc))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .addAction(action)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(AdbPairingReceiver.NOTIFICATION_ID, builder.build());
    }

    private void stopDiscovery() {
        try {
            if (connectDiscoveryListener != null) {
                nsdManager.stopServiceDiscovery(connectDiscoveryListener);
                connectDiscoveryListener = null;
            }
            if (pairingDiscoveryListener != null) {
                nsdManager.stopServiceDiscovery(pairingDiscoveryListener);
                pairingDiscoveryListener = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        }
    }

    private void checkIfScanTimedOut() {
        if (isScanning && (discoveredConnectPort == -1 || discoveredPairingPort == -1)) {
            Snackbar.make(getView(), R.string.adb_toast_scan_timeout, Snackbar.LENGTH_LONG).show();
            stopDiscovery();
            resetScanState();
        }
    }

    private void resetScanState() {
        isScanning = false;
        if (!isConnectedToAdb) {
            btnAdbAction.setEnabled(true);
            btnAdbAction.setText(getString(R.string.adb_btn_connect));
        }
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

    private void addCpuEntry(float cpuPercentage) {
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
        return isDownloadingRootfs || isBatchInstalling || isBackupInProgress || isRestoring || isDeleting || isImporting;
    }

    public String getSystemBusyMessage() {
        if (isDownloadingRootfs) return getString(R.string.install_busy_provisioning);
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

    private String getLocalWifiIp() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0)
                return String.format(java.util.Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        }
        return "127.0.0.1";
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

    private float parseCpuUsage(String cpuLine) {
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
    @Override public InstallationPlanner.Tier getSelectedTier() { return selectedTier; }
    @Override public void setSelectedTier(InstallationPlanner.Tier tier) { this.selectedTier = tier; }
    @Override public java.util.List<android.widget.CheckBox> moduleCheckboxes() { return newInstallCheckboxes; }
    @Override public void setStorageSafe(boolean safe) { this.isStorageSafe = safe; }
    @Override public boolean isStorageSafe() { return this.isStorageSafe; }
    @Override public String getOverrideKiwixLang() { return overrideKiwixLang; }
    @Override public void setOverrideKiwixLang(String lang) { this.overrideKiwixLang = lang; }
    @Override public String getOverrideKiwixVariant() { return overrideKiwixVariant; }
    @Override public void setOverrideKiwixVariant(String variant) { this.overrideKiwixVariant = variant; }
    @Override public boolean hasInternet() { return this.hasInternet; }

    // --- InstallHost seam (install pipeline lives in InstallController) ---
    @Override public boolean isDownloadingRootfs() { return isDownloadingRootfs; }
    @Override public void setDownloadingRootfs(boolean v) { isDownloadingRootfs = v; }
    @Override public boolean isBatchInstalling() { return isBatchInstalling; }
    @Override public void setBatchInstalling(boolean v) { isBatchInstalling = v; }
    @Override public java.util.List<String> installationQueue() { return installationQueue; }
    @Override public org.json.JSONObject getLastKnownState() { return lastKnownState; }
    @Override public void setLastKnownState(org.json.JSONObject v) { lastKnownState = v; }
    @Override public org.iiab.controller.Aria2Manager aria2Manager() { return aria2Manager; }
    @Override public void setAria2Manager(org.iiab.controller.Aria2Manager v) { aria2Manager = v; }
    @Override public org.iiab.controller.PRootEngine prootEngine() { return prootEngine; }
    @Override public void setPRootEngine(org.iiab.controller.PRootEngine v) { prootEngine = v; }
}
