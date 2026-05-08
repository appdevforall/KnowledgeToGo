/*
 * ============================================================================
 * Name        : DeployFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Installation / deployment view
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
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

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DeployFragment extends Fragment {

    // --- UI Variables ---
    private View ledInternet;
    private View ledDevMode;
    private View ledDcpr;
    private View ledPpk;
    private TextView txtDcpr;
    private TextView txtPpk;
    private LinearLayout rolesContainer;
    private LinearLayout discrepancyWarning;
    private Button btnLaunchInstall;
    private Button btnFastInstall;
    private Button btnFastDelete;
    private Button btnAdvancedReset;
    private Button btnAdvancedBackup;
    private Button btnAdvancedRestore;
    private Button btnAdvancedForceStop;

    // Restore backups menu
    private TextView txtSelectBackupTitle;
    private LinearLayout containerBackupList;
    private TextView txtBackupStatus;
    private String selectedBackupFile = null;

    // --- State Management ---
    private final List<CheckBox> newInstallCheckboxes = new ArrayList<>();
    private File sharedStateDir;
    private JSONObject lastKnownState = new JSONObject();
    private TextView btnRefreshModules;
    private static final String TAG = "IIAB-DeployFragment";
    private List<String> installationQueue = new ArrayList<>();
    // --- NATIVE DOWNLOADER VARIABLES ---
    private static Aria2Manager aria2Manager;
    private static boolean isDownloadingRootfs = false;
    private PRootEngine prootEngine;
    private boolean isBatchInstalling = false;
    private final Handler liveStatusHandler = new Handler(Looper.getMainLooper());
    private Runnable liveStatusRunnable;
    // -- Advance monitoring --
    private Button btnAdbAction;
    private View ledAdbStatus;
    private TextView txtAdbLedLabel;
    private com.github.mikephil.charting.charts.LineChart cpuChart;

    // --- INSTALLATION PLANNER ---
    private Button btnTierBasic, btnTierStandard, btnTierFull;
    private TextView txtLegendIiab, txtLegendMaps, txtLegendKiwix, txtLegendFree;
    private CheckBox chkCompanionData;
    private MultiResourceGaugeView storageGauge;
    private boolean isStorageSafe = false;
    private android.widget.ImageButton btnKiwixSettings;
    private String overrideKiwixLang = null;
    private String overrideKiwixVariant = null;
    private InstallationPlanner.Tier selectedTier = null;

    private boolean isConnectedToAdb = false;
    private boolean isScanning = false;
    private boolean isAttemptingFastConnect = false;
    private android.net.nsd.NsdManager nsdManager;
    private String discoveredHostIp = "127.0.0.1";
    private int discoveredConnectPort = -1;
    private int discoveredPairingPort = -1;
    private android.net.nsd.NsdManager.DiscoveryListener connectDiscoveryListener;
    private android.net.nsd.NsdManager.DiscoveryListener pairingDiscoveryListener;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    private static final String CHANNEL_ID = "adb_pairing_channel";
    private static final String SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp.";
    private static final String SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp.";

    private final BroadcastReceiver adbUiUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if ("org.iiab.controller.ADB_PAIRING_SUCCESSFUL".equals(action)) {
                // Clear memory because we caught the live broadcast
                requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("pairing_just_succeeded", false).apply();

                android.util.Log.i(TAG, "Broadcast received: Pairing successful! Re-scanning in 2.5s...");
                if (isAdded()) {
                    btnAdbAction.setText("Securing connection...");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startAdbPairingFlow();
                    }, 2500);
                }
            } else if ("org.iiab.controller.ADB_PAIRING_FAILED".equals(action)) {
                android.util.Log.w(TAG, "Broadcast received: Pairing failed.");
                if (isAdded()) resetScanState();

            } else if ("org.iiab.controller.ADB_PAIRING_SENT".equals(action)) {
                btnAdbAction.setText("Connected!");
                isConnectedToAdb = true;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isAdded()) updateUiState(true);
                }, 500);

            } else if ("org.iiab.controller.ADB_CPU_UPDATE".equals(action)) {
                // ... (Keep your existing CPU update logic here)
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

                String ppkDisplay = ("null".equals(rawPpkValue) || "unknown".equals(rawPpkValue)) ? getString(R.string.adb_ppk_default) : rawPpkValue;

                // Reset tints and set base grey background
                ledDcpr.setBackgroundTintList(null);
                ledPpk.setBackgroundTintList(null);
                ledPpk.setBackgroundResource(R.drawable.led_off);
                ledDcpr.setBackgroundResource(R.drawable.led_off);

                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    // ==========================================
                    // --- ANDROID 14+ LOGIC ---
                    // ==========================================
                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_not_required, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));

                    // PPK Color Logic (A14+)
                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green); // GREEN (Fixed manually)
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107"))); // YELLOW (Timeout/Error)
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3"))); // BLUE (Queried, default value, not critical here)
                    }

                    // Child Process Color Logic (A14+)
                    if ("0".equals(cpValue) || "false".equals(cpValue)) {
                        ledDcpr.setBackgroundResource(R.drawable.led_on_green);
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_disabled_ok), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else if ("1".equals(cpValue) || "true".equals(cpValue) || "null".equals(cpValue) || cpValue == null) {
                        ledDcpr.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336"))); // RED (Limiting)
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_enabled_limiting), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else {
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_unknown), android.text.Html.FROM_HTML_MODE_COMPACT));
                    }

                } else if (android.os.Build.VERSION.SDK_INT >= 31) {
                    // ==========================================
                    // --- ANDROID 12 & 13 LOGIC ---
                    // ==========================================
                    txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_not_required), android.text.Html.FROM_HTML_MODE_COMPACT));

                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_active, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));

                    // PPK Color Logic (A12/13)
                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green); // GREEN (Fixed manually)
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336"))); // RED (Timeout/Error - Critical here)
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107"))); // YELLOW (Queried, default value, needs attention)
                    }
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_deploy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1. SINGLE VIEW INITIALIZATION
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
        nsdManager = (android.net.nsd.NsdManager) requireContext().getSystemService(Context.NSD_SERVICE);

        btnKiwixSettings = view.findViewById(R.id.btn_kiwix_settings);
        btnKiwixSettings.setOnClickListener(v -> showKiwixSettingsDialog());

        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("iiab_adb_multicast_lock");
            multicastLock.setReferenceCounted(true);
        }

        // 2. VERSION LOGIC (Hide on Android 10-)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            view.findViewById(R.id.section_adv_monitoring).setVisibility(View.GONE);

            // Hide the entire top LED banner for devices without wireless ADB
            View adbLedsContainer = view.findViewById(R.id.container_adb_leds);
            if (adbLedsContainer != null) {
                adbLedsContainer.setVisibility(View.GONE);
            }
        } else {
            TextView txtAdvMonitoringTitle = view.findViewById(R.id.txt_adv_monitoring_title);
            LinearLayout containerAdvMonitoring = view.findViewById(R.id.container_adv_monitoring);
            setupSingleMenu(txtAdvMonitoringTitle, containerAdvMonitoring, R.string.install_adv_monitoring_title);
        }

        // 3. ASSIGN TEXT LISTENERS
        LinearLayout containerDcpr = view.findViewById(R.id.container_led_dcpr);
        containerDcpr.setOnClickListener(v -> {
            if (isConnectedToAdb && android.os.Build.VERSION.SDK_INT >= 34) {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
                adbManager.executeCommand("settings put global settings_enable_monitor_phantom_procs 0");
                com.google.android.material.snackbar.Snackbar.make(v, R.string.adb_snack_disabling_cp, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
            }
        });

        // Listener to set PPK to 256 (Android 12+)
        LinearLayout containerPpk = view.findViewById(R.id.container_led_ppk);
        containerPpk.setOnClickListener(v -> {
            if (isConnectedToAdb && android.os.Build.VERSION.SDK_INT >= 31) {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
                adbManager.executeCommand("device_config put activity_manager max_phantom_processes 256");
                com.google.android.material.snackbar.Snackbar.make(v, R.string.adb_snack_setting_ppk, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
            }
        });

        // 4. INITIALIZE CHART AND ADB BUTTON
        setupCpuChart();

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

        rolesContainer = view.findViewById(R.id.install_roles_container);
        discrepancyWarning = view.findViewById(R.id.install_discrepancy_warning);
        btnLaunchInstall = view.findViewById(R.id.btn_launch_install);

        // Link fast install / delete buttons
        btnFastInstall = view.findViewById(R.id.btn_fast_install);
        btnFastDelete = view.findViewById(R.id.btn_fast_delete);

        btnAdvancedReset = view.findViewById(R.id.btn_advanced_reset);
        btnAdvancedBackup = view.findViewById(R.id.btn_advanced_backup);
        btnAdvancedRestore = view.findViewById(R.id.btn_advanced_restore);
        btnAdvancedForceStop = view.findViewById(R.id.btn_advanced_force_stop);
        txtSelectBackupTitle = view.findViewById(R.id.txt_select_backup_title);
        containerBackupList = view.findViewById(R.id.container_backup_list);
        txtBackupStatus = view.findViewById(R.id.txt_backup_status);

        sharedStateDir = new File(Environment.getExternalStorageDirectory(), ".iiab_state");
        btnRefreshModules = view.findViewById(R.id.btn_refresh_modules);

        // Initial Button State
        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setAlpha(0.5f);

        btnKiwixSettings.setColorFilter(Color.parseColor("#555555"));
        btnFastInstall.setAlpha(0.4f);

        setupAllCollapsibleMenus();
        createModulesGrid();
        requestFreshLocalVarsSilently();

        // Define the heartbeat (checks the server in the background)
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
            // Check every 3 seconds
            liveStatusHandler.postDelayed(liveStatusRunnable, 3000);
        };

        // Initialize Planner UI
        btnTierBasic = view.findViewById(R.id.btn_tier_basic);
        btnTierStandard = view.findViewById(R.id.btn_tier_standard);
        btnTierFull = view.findViewById(R.id.btn_tier_full);
        chkCompanionData = view.findViewById(R.id.chk_companion_data);
        storageGauge = view.findViewById(R.id.storage_projection_gauge);

        txtLegendIiab = view.findViewById(R.id.txt_legend_iiab);
        txtLegendMaps = view.findViewById(R.id.txt_legend_maps);
        txtLegendKiwix = view.findViewById(R.id.txt_legend_kiwix);
        txtLegendFree = view.findViewById(R.id.txt_legend_free);

        setupPlannerListeners();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Hide warning initially
        if (discrepancyWarning != null) discrepancyWarning.setVisibility(View.GONE);

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

        // --- THE MEMORY BRIDGE ---
        android.content.SharedPreferences adbPrefs = requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE);
        if (adbPrefs.getBoolean("pairing_just_succeeded", false)) {
            // Clear the flag
            adbPrefs.edit().putBoolean("pairing_just_succeeded", false).apply();

            android.util.Log.i(TAG, "Recovered successful pairing state. Silent Re-scanning...");
            btnAdbAction.setText("Securing connection...");
            // Execute silent scan (true) so it doesn't reopen settings
            new Handler(Looper.getMainLooper()).postDelayed(() -> startAdbPairingFlow(true), 2500);
        }

        // Restore the memory queue
        restoreQueueFromPrefs();

        if (isBatchInstalling) {
            new Handler(Looper.getMainLooper()).postDelayed(this::processNextInQueue, 500);
        }

        if (lastKnownState.length() > 0) {
            verifyInstallationState(lastKnownState);
        } else {

            File jsonFile = new File(sharedStateDir, "local_vars.json");
            if (jsonFile.exists() && jsonFile.length() > 0) {
                try {
                    StringBuilder text = new StringBuilder();
                    BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                    String line;
                    while ((line = br.readLine()) != null) {
                        text.append(line);
                    }
                    br.close();
                    lastKnownState = new JSONObject(text.toString());
                    verifyInstallationState(lastKnownState);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Failed to read existing JSON in onResume", e);
                }
            }
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

    private void setupAllCollapsibleMenus() {
        if (getView() == null) return;
        // 2. Modules
        TextView txtModuleMgmtTitle = getView().findViewById(R.id.txt_module_mgmt_title);
        LinearLayout containerModuleMgmt = getView().findViewById(R.id.container_module_mgmt);
        setupSingleMenu(txtModuleMgmtTitle, containerModuleMgmt, R.string.install_header_roles);

        // 3. Maintenance
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

            if (isCollapsed) {
                titleView.setText(getString(R.string.label_separator_down, baseText));
            } else {
                titleView.setText(getString(R.string.label_separator_up, baseText));
            }
        });
    }

    private void createModulesGrid() {
        if (rolesContainer == null || getContext() == null) return;
        rolesContainer.removeAllViews();
        newInstallCheckboxes.clear();

        boolean isServerRunning = false;
        if (getActivity() instanceof MainActivity) {
            isServerRunning = ((MainActivity) getActivity()).isServerAlive;
        }

        String termuxArch = getTermuxArch();
        boolean is64Bit = termuxArch != null && termuxArch.contains("64");

        List<ModuleRegistry.IiabModule> activeModules = new ArrayList<>();
        for (ModuleRegistry.IiabModule module : ModuleRegistry.MASTER_ROSTER) {
            if (module.requires64Bit && !is64Bit) continue;
            activeModules.add(module);
        }

        int numCols = 3;
        int numRows = (int) Math.ceil((double) activeModules.size() / numCols);

        int ledSizePx = (int) (12 * getResources().getDisplayMetrics().density);

        for (int row = 0; row < numRows; row++) {
            LinearLayout rowLayout = new LinearLayout(requireContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rowLayout.setBaselineAligned(false);
            rowLayout.setWeightSum(numCols);
            rowLayout.setPadding(0, 0, 0, 16);

            for (int col = 0; col < numCols; col++) {
                int index = (row * numCols) + col;

                LinearLayout cell = new LinearLayout(requireContext());
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                int margin = 10;
                if (col == 0) cellParams.setMargins(0, 0, margin, 0);
                else if (col == 1) cellParams.setMargins(margin / 2, 0, margin / 2, 0);
                else cellParams.setMargins(margin, 0, 0, 0);

                cell.setLayoutParams(cellParams);

                if (index < activeModules.size()) {
                    ModuleRegistry.IiabModule currentMod = activeModules.get(index);

                    cell.setOrientation(LinearLayout.HORIZONTAL);
                    cell.setBackgroundResource(R.drawable.rounded_button);
                    cell.setBackgroundTintList(ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.dash_module_bg)));
                    cell.setPadding(16, 28, 16, 28);
                    cell.setGravity(android.view.Gravity.CENTER);

                    int boxSizePx = (int) (24 * getResources().getDisplayMetrics().density);
                    android.widget.FrameLayout indicatorContainer = new android.widget.FrameLayout(requireContext());
                    LinearLayout.LayoutParams indParams = new LinearLayout.LayoutParams(boxSizePx, boxSizePx);
                    indicatorContainer.setLayoutParams(indParams);

                    View led = new View(requireContext());
                    android.widget.FrameLayout.LayoutParams ledParams = new android.widget.FrameLayout.LayoutParams(
                            ledSizePx, ledSizePx, android.view.Gravity.CENTER);
                    led.setLayoutParams(ledParams);
                    led.setBackgroundResource(R.drawable.led_off);

                    CheckBox checkBox = new CheckBox(requireContext());
                    checkBox.setScaleX(0.85f);
                    checkBox.setScaleY(0.85f);
                    checkBox.setPadding(0, 0, 0, 0);
                    android.widget.FrameLayout.LayoutParams cbParams = new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER);
                    checkBox.setLayoutParams(cbParams);
                    checkBox.setVisibility(View.GONE);

                    if (isServerRunning) {
                        checkBox.setEnabled(false);
                        cell.setAlpha(0.6f);
                    } else {
                        checkBox.setEnabled(true);
                        cell.setAlpha(1.0f);
                    }

                    indicatorContainer.addView(led);
                    indicatorContainer.addView(checkBox);

                    TextView name = new TextView(requireContext());
                    name.setText(getString(currentMod.nameResId));
                    name.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                    name.setTextSize(12f);
                    name.setSingleLine(true);

                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    textParams.setMargins(16, 0, 0, 0);
                    name.setLayoutParams(textParams);

                    cell.addView(indicatorContainer);
                    cell.addView(name);

                    cell.setTag(currentMod);
                } else {
                    cell.setVisibility(View.INVISIBLE);
                }

                rowLayout.addView(cell);
            }
            rolesContainer.addView(rowLayout);
        }
    }

    // --- NATIVE LOGIC: READ VARIABLES DIRECTLY FROM FILESYSTEM ---
    private void fetchLocalVarsFromPRoot() {
        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        File localVarsFile = new File(rootfsDir, "etc/iiab/local_vars.yml");

        // If the Debian system or the configuration file doesn't exist, send an empty state to the UI
        if (!rootfsDir.exists() || !rootfsDir.isDirectory() || !localVarsFile.exists()) {
            lastKnownState = new JSONObject();
            verifyInstallationState(lastKnownState);
            return;
        }

        new Thread(() -> {
            try {
                // Read the file directly from the Android host filesystem
                // This avoids invoking PRoot, preventing accidental service startups or data corruption
                StringBuilder yamlOutput = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(localVarsFile));
                String line;
                while ((line = br.readLine()) != null) {
                    yamlOutput.append(line).append("\n");
                }
                br.close();

                // Parse the captured text converting it to JSON in memory
                JSONObject freshVars = parseYamlToJson(yamlOutput.toString());
                lastKnownState = freshVars;

                if (getActivity() instanceof MainActivity) {
                    getActivity().getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_module_state_trusted", true)
                            .apply();
                }

                // Dispatch the result to the UI without touching the hard drive again
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> verifyInstallationState(freshVars));
                }

            } catch (Exception e) {
                android.util.Log.e(TAG, "Native error reading local_vars directly: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> verifyInstallationState(lastKnownState));
                }
            }
        }).start();
    }

    // Ultra-fast YAML to JSON translator
    private JSONObject parseYamlToJson(String yaml) {
        JSONObject json = new JSONObject();
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            // Look for "key: value" formats, ignoring comments
            if (line.contains(":") && !line.trim().startsWith("#")) {
                String[] parts = line.split(":", 2);
                String key = parts[0].trim();
                String val = parts[1].trim().toLowerCase();

                // Filter only the variables IIAB needs
                if (key.endsWith("_install") || key.endsWith("_enabled")) {
                    try {
                        boolean isTrue = val.equals("true") || val.equals("yes") || val.equals("1");
                        json.put(key, isTrue);
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "Error parsing YAML line: " + line);
                    }
                }
            }
        }
        return json;
    }

    private void requestFreshLocalVars() {
        // Now we simply call our reader into memory.
        fetchLocalVarsFromPRoot();
    }

    private void requestFreshLocalVarsSilently() {
        fetchLocalVarsFromPRoot();
    }

    // --- LOGIC: VERIFY AND COLORIZE ---
    private void verifyInstallationState(JSONObject jsonVars) {
        new Thread(() -> {
            if (!isAdded() || getActivity() == null || rolesContainer == null) return;

            boolean isMainServerAlive = pingUrl("http://localhost:8085/home");
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

                    boolean pingState = isMainServerAlive && pingUrl("http://localhost:8085/" + module.endpoint);

                    // Get global state before entering UI thread
                    MainActivity mainAct = (MainActivity) getActivity();
                    boolean isRunning = mainAct != null && mainAct.isServerAlive;
                    boolean isTrusted = mainAct != null && mainAct.isModuleStateTrusted();

                    // LOGIC FIX: Separate rules for On/Off states
                    boolean isConfirmedInstalled;
                    boolean isDiscrepancy;

                    if (isRunning) {
                        // Server ON: YAML and Ping must match perfectly
                        isConfirmedInstalled = yamlState && pingState;
                        isDiscrepancy = yamlState != pingState;
                    } else {
                        // Server OFF: Pings will fail. Trust YAML and memory state.
                        isConfirmedInstalled = yamlState;
                        isDiscrepancy = yamlState && !isTrusted;
                    }

                    // Freeze variables to pass them to the UI thread
                    final boolean finalConfirmed = isConfirmedInstalled;
                    final boolean finalDiscrepancyFlag = isDiscrepancy;
                    final boolean finalIsRunning = isRunning;

                    getActivity().runOnUiThread(() -> {
                        card.setOnClickListener(null);
                        checkBox.setOnCheckedChangeListener(null);

                        if (finalConfirmed && !finalDiscrepancyFlag) {
                            // RULE 1: Installed and Trusted
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundTintList(null);

                            if (finalIsRunning) {
                                // Live absolute certainty (GREEN)
                                led.setBackgroundResource(R.drawable.led_on_green);
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_confirmed, Snackbar.LENGTH_LONG).show());
                            } else {
                                // Offline but trusted (PURPLE)
                                led.setBackgroundResource(R.drawable.led_on_green); // Use green as base shape...
                                led.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#9C27B0"))); // ... and tint it purple
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_offline_trusted, Snackbar.LENGTH_LONG).show());
                            }
                        } else if (finalDiscrepancyFlag) {
                            // RULE 2: Real discrepancy or untrusted memory (YELLOW/ORANGE)
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundResource(R.drawable.led_off);
                            led.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));

                            card.setOnClickListener(v -> Snackbar.make(v, R.string.install_warning_discrepancy_msg, Snackbar.LENGTH_LONG).show());
                        } else {
                            // RULE 3: Available to install (CHECKBOX)
                            led.setVisibility(View.GONE);
                            checkBox.setVisibility(View.VISIBLE);
                            checkBox.setChecked(false);

                            // Security lock if server is running
                            if (finalIsRunning) {
                                checkBox.setEnabled(false);
                                card.setAlpha(0.6f);
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show());
                            } else {
                                checkBox.setEnabled(true);
                                card.setAlpha(1.0f);
                                checkBox.setButtonTintList(ColorStateList.valueOf(Color.WHITE));
                                card.setOnClickListener(v -> checkBox.toggle());
                            }

                            if (!newInstallCheckboxes.contains(checkBox)) {
                                newInstallCheckboxes.add(checkBox);
                            }
                            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> evaluateLaunchButton());
                        }
                    });

                    if (finalDiscrepancyFlag) {
                        discrepancyFound = true;
                    }
                }
            }

            final boolean finalDiscrepancy = discrepancyFound;
            getActivity().runOnUiThread(() -> {
                if (discrepancyWarning != null) {
                    discrepancyWarning.setVisibility(finalDiscrepancy ? View.VISIBLE : View.GONE);
                }
                evaluateLaunchButton();
            });

        }).start();
    }

    private void evaluateLaunchButton() {
        if (isBatchInstalling) return;

        boolean hasSelections = false;
        installationQueue.clear();

        for (CheckBox cb : newInstallCheckboxes) {
            if (cb.isChecked()) {
                hasSelections = true;
                ViewGroup indicatorContainer = (ViewGroup) cb.getParent();
                ViewGroup card = (ViewGroup) indicatorContainer.getParent();
                ModuleRegistry.IiabModule module = (ModuleRegistry.IiabModule) card.getTag();

                if (module != null) {
                    installationQueue.add(module.yamlBaseKey);
                }
            }
        }

        btnLaunchInstall.setEnabled(hasSelections);
        btnLaunchInstall.setAlpha(hasSelections ? 1.0f : 0.5f);
        btnLaunchInstall.setText(getString(R.string.install_btn_launch));

        if (hasSelections) {
            btnLaunchInstall.setOnClickListener(v -> {
                isBatchInstalling = true;
                saveQueueToPrefs();
                processNextInQueue();
            });
        } else {
            btnLaunchInstall.setOnClickListener(null);
        }
    }

    private boolean pingUrl(String urlStr) {
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

    private String getTermuxArch() {
        try {
            // FIX: Inspect our own native app architecture, not external Termux
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
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error obtaining native architecture", e);
        }

        if (android.os.Build.SUPPORTED_ABIS.length > 0) {
            return android.os.Build.SUPPORTED_ABIS[0];
        }
        return "unknown";
    }

    // --- METHODS FOR PERSISTING THE INSTALLATION QUEUE ---
    private void saveQueueToPrefs() {
        if (getActivity() == null) return;
        android.content.SharedPreferences prefs = getActivity().getSharedPreferences("iiab_queue_prefs", android.content.Context.MODE_PRIVATE);
        String queueString = android.text.TextUtils.join(",", installationQueue);
        prefs.edit().putString("pending_modules", queueString).putBoolean("is_batch_installing", isBatchInstalling).apply();
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

    // --- QUEUE DIRECTOR ---
    private void processNextInQueue() {
        if (installationQueue.isEmpty()) {
            // The queue is empty! Batch installation is complete.
            isBatchInstalling = false;
            saveQueueToPrefs();

            btnLaunchInstall.setEnabled(false);
            btnLaunchInstall.setText(getString(R.string.install_btn_launch));

            // Refresh UI LEDs using our new in-memory YAML reader
            fetchLocalVarsFromPRoot();

            if (getView() != null) {
                Snackbar.make(getView(), R.string.install_msg_finished, Snackbar.LENGTH_LONG).show();
            }
            return;
        }

        // Pop the next module from the queue
        String nextModule = installationQueue.remove(0);
        saveQueueToPrefs();

        android.util.Log.d(TAG, "Installing module natively: " + nextModule);

        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setText("Installing " + nextModule + "...");

        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        if (prootEngine == null) prootEngine = new PRootEngine();

        // Build the command combining sed injection and Ansible execution
        String installCmd =
                "sed -i -E '/^[[:space:]]*" + nextModule + "_(install|enabled)[[:space:]]*:/d' /etc/iiab/local_vars.yml && " +
                        "echo '" + nextModule + "_install: True' >> /etc/iiab/local_vars.yml && " +
                        "echo '" + nextModule + "_enabled: True' >> /etc/iiab/local_vars.yml && " +
                        "cd /opt/iiab/iiab && ./runrole " + nextModule;

        prootEngine.executeInContainer(requireContext(), rootfsDir.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                // Capture Ansible output and route it to the app's Log panel
                android.util.Log.i("IIAB-Ansible", line);
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).runOnUiThread(() -> ((MainActivity) getActivity()).addToLog("[Ansible] " + line));
                }
            }

            @Override
            public void onProcessExit(int exitCode) {
                // Recursive loop: proceed to the next module when the current one finishes
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> processNextInQueue());
                }
            }

            @Override
            public void onError(String error) {
                android.util.Log.e(TAG, "Fatal PRoot error while executing module: " + error);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isBatchInstalling = false;
                        updateDynamicButtons();
                        if (getView() != null)
                            Snackbar.make(getView(), "Error: " + error, Snackbar.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    /**
     * Master UI Controller for the Deployment Fragment.
     */
    private void updateDynamicButtons() {
        MainActivity mainAct = (MainActivity) getActivity();
        if (mainAct == null || !isAdded()) return;

        boolean isServerRunning = mainAct.isServerAlive;

        // 1. Define paths correctly exactly ONCE to prevent "already defined" scope errors
        final File iiabRootDir = new File(requireContext().getFilesDir(), "rootfs");
        final File debianRootfs = new File(iiabRootDir, "installed-rootfs/iiab");
        final File backupsDir = new File(iiabRootDir, "backups");
        if (!backupsDir.exists()) backupsDir.mkdirs();

        // 2. Check if OS is installed.
        boolean isProotInstalled = new File(debianRootfs, "etc/os-release").exists() || new File(debianRootfs, "usr/bin/bash").exists();

        refreshDashboardLeds(mainAct);

        // LOGIC OF THE ANIMATED BANNER
        View bannerWarning = getView().findViewById(R.id.banner_server_warning);
        if (bannerWarning != null) {
            boolean isBannerVisible = bannerWarning.getVisibility() == View.VISIBLE;

            // Animate only if there is a real state change to prevent screen flashing.
            if (isServerRunning && !isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.VISIBLE);
            } else if (!isServerRunning && isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.GONE);
            }
        }

        // --- REFRESH BUTTON LOGIC ---
        if (btnRefreshModules != null) {
            btnRefreshModules.setEnabled(true);

            // We keep the UI lock if the server is running to prevent visual inconsistencies
            if (isServerRunning || !isProotInstalled) {
                btnRefreshModules.setTextColor(Color.parseColor("#9E9E9E"));
                btnRefreshModules.setAlpha(0.6f);
                btnRefreshModules.setOnClickListener(v -> {
                    if (!isProotInstalled)
                        Snackbar.make(v, R.string.install_msg_termux_missing, Snackbar.LENGTH_LONG).show();
                    else if (isServerRunning)
                        Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                });
            } else {
                btnRefreshModules.setTextColor(Color.parseColor("#2196F3"));
                btnRefreshModules.setAlpha(1.0f);
                btnRefreshModules.setOnClickListener(v -> {
                    v.setAlpha(0.5f);
                    requestFreshLocalVars();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> v.setAlpha(1.0f), 1000);
                });
            }
        }

        // --- ADVANCED SECTION LOGIC (2x2 Grid) ---
        btnFastInstall.setEnabled(true);
        btnFastDelete.setEnabled(true);
        if (btnAdvancedReset != null) btnAdvancedReset.setEnabled(true);
        if (btnAdvancedBackup != null) btnAdvancedBackup.setEnabled(true);
        if (btnAdvancedRestore != null) btnAdvancedRestore.setEnabled(true);
        if (txtSelectBackupTitle != null) txtSelectBackupTitle.setEnabled(true);

        // Force Stop is always active as an emergency exit
        if (btnAdvancedForceStop != null) {
            btnAdvancedForceStop.setEnabled(true);
            btnAdvancedForceStop.setAlpha(1.0f);
            btnAdvancedForceStop.setOnClickListener(v -> openTermuxAppInfo());
        }

        if (isServerRunning) {
            // CASE A: Server Running (Security lock)
            float lockAlpha = 0.5f;
            btnFastInstall.setAlpha(lockAlpha);
            btnFastDelete.setAlpha(lockAlpha);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(lockAlpha);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setAlpha(lockAlpha);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha(lockAlpha);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(lockAlpha);

            btnFastInstall.setText(R.string.install_btn_reinstall);

            View.OnClickListener serverHot = v -> Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
            btnFastInstall.setOnClickListener(serverHot);
            btnFastDelete.setOnClickListener(serverHot);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setOnClickListener(serverHot);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setOnClickListener(serverHot);
            if (btnAdvancedReset != null) btnAdvancedReset.setOnClickListener(serverHot);
            if (txtSelectBackupTitle != null)
                txtSelectBackupTitle.setOnClickListener(serverHot);

        } else {
            // CASE B: Clear Path (Server Offline)
            if (selectedTier == null || !isStorageSafe) {
                btnFastInstall.setAlpha(0.4f);
            } else {
                btnFastInstall.setAlpha(1.0f);
            }

            btnFastDelete.setAlpha(1.0f);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(1.0f);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha(1.0f);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(1.0f);
            refreshRestoreButtonLogic();

            // Restore button starts locked until a valid backup is selected
            if (btnAdvancedRestore != null) {
                btnAdvancedRestore.setAlpha(0.5f);
                btnAdvancedRestore.setOnClickListener(v -> {
                    Snackbar.make(v, "Please select a backup first.", Snackbar.LENGTH_LONG).show();
                });
            }

            if (!isDownloadingRootfs) {
                btnFastInstall.setEnabled(true);
                btnFastInstall.setTextSize(14f);
                if (isProotInstalled) {
                    btnFastInstall.setText(R.string.install_btn_reinstall);
                } else {
                    btnFastInstall.setText(R.string.install_btn_install);
                }
            } else {
                // If the screen reloaded due to the dark theme, remember that we are downloading
                btnFastInstall.setEnabled(true);
                btnFastInstall.setTextSize(12f);
                btnFastInstall.setAlpha(0.8f);
            }

            // STEP 1: INSTALL (PURE JAVA RE-IMPLEMENTATION)
            btnFastInstall.setOnClickListener(v -> {
                if (selectedTier == null) {
                    Snackbar.make(v, "Hey! Select an edition to install (Basic, Standard, or Full).", Snackbar.LENGTH_LONG).show();
                    return;
                }
                if (!isStorageSafe) {
                    Snackbar.make(v, "Not enough storage! Please free up space or choose a smaller edition. (5GB safety buffer required)", Snackbar.LENGTH_LONG).show();
                    return; // Abort execution
                }
                if (isDownloadingRootfs) {
                    // IF IT IS IN PROGRESS: Request confirmation to cancel
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.install_btn_cancel_title))
                            .setMessage(getString(R.string.install_btn_cancel_msg))
                            .setPositiveButton(getString(R.string.install_btn_cancel_confirm), (dialog, which) -> {
                                if (aria2Manager != null) {
                                    aria2Manager.stopDownload();
                                }
                                isDownloadingRootfs = false;
                                btnFastInstall.setText(R.string.install_btn_install);
                                btnFastInstall.setAlpha(1.0f);
                                Snackbar.make(getView(), R.string.install_msg_cancelled, Snackbar.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show();
                    return;
                }

                // Native Installation Master Routine
                Runnable executeDownload = () -> {
                    mainAct.invalidateModuleStateTrust();
                    isDownloadingRootfs = true;
                    btnFastInstall.setAlpha(0.8f);
                    btnFastInstall.setTextSize(12f);

                    if (aria2Manager == null) aria2Manager = new Aria2Manager();

                    // 1. CONSTRUCT METADATA URL BASED ON TIER AND ARCHITECTURE
                    String arch = getTermuxArch();
                    String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "armeabi-v7a" : "arm64-v8a";

                    InstallationPlanner.Tier safeTier = (selectedTier != null) ? selectedTier : InstallationPlanner.Tier.BASIC;
                    String tierString = safeTier.name().toLowerCase(java.util.Locale.US);

                    String directUrl = "https://iiab.switnet.org/android/rootfs/latest_" + tierString + "_" + archSuffix + ".meta4";

                    // PIPELINE PHASE 1: Download Base System
                    aria2Manager.startDownload(requireContext(), directUrl, new Aria2Manager.DownloadListener() {
                        @Override
                        public void onProgress(int percentage, String speed, String eta) {
                            if (isAdded() && getActivity() != null) {
                                getActivity().runOnUiThread(() -> btnFastInstall.setText("OS: " + percentage + "% | " + speed + "/s"));
                            }
                        }

                        @Override
                        public void onComplete(String downloadPath) {
                            if (!isAdded() || getActivity() == null) return;
                            mainAct.runOnUiThread(() -> btnFastInstall.setText("Extracting System... \n(This takes a while)"));

                            File downloadDir = new File(downloadPath);
                            File[] archives = downloadDir.listFiles((dir, name) -> name.endsWith(".tar.xz") || name.endsWith(".tar.gz"));

                            if (archives == null || archives.length == 0) {
                                abortInstallation("Error: Downloaded archive not found.");
                                return;
                            }

                            File downloadedArchive = archives[0];
                            TarExtractor tarExtractor = new TarExtractor();

                            // PIPELINE PHASE 2: Extract OS
                            tarExtractor.startExtraction(requireContext(), downloadedArchive.getAbsolutePath(), iiabRootDir.getAbsolutePath(), new TarExtractor.ExtractionListener() {
                                @Override
                                public void onComplete(String destDir) {
                                    downloadedArchive.delete();

                                    File prootTmp = new File(requireContext().getCacheDir(), "proot_tmp");
                                    if (!prootTmp.exists()) prootTmp.mkdirs();
                                    File binDir = new File(requireContext().getFilesDir(), "usr/bin");
                                    if (binDir.exists()) {
                                        try { Runtime.getRuntime().exec(new String[]{"chmod", "-R", "755", binDir.getAbsolutePath()}).waitFor(); } catch (Exception ignored) {}
                                    }

                                    // PIPELINE PHASE 3: Companion Data
                                    if (chkCompanionData.isChecked()) {

                                        // A. Edit YAML safely via Regex BEFORE booting PRoot
                                        editLocalVarsForMaps(debianRootfs, safeTier);

                                        // B. Retrieve the exact ZIM filename to download
                                        android.content.SharedPreferences prefs = requireContext().getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                                        String targetLang = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", "en");

                                        InstallationPlanner.calculateProjectedSize(requireContext(), safeTier, true, targetLang, overrideKiwixVariant, new InstallationPlanner.PlanResultListener() {
                                            @Override
                                            public void onCalculated(InstallationPlanner.StorageProjection projection) {
                                                if (projection.resolvedFilename != null) {
                                                    // Proceed to Kiwix Download -> Index -> Maps Ansible
                                                    downloadAndIndexKiwix(projection.resolvedFilename, debianRootfs);
                                                } else {
                                                    // No ZIM found, skip directly to Maps Ansible
                                                    runMapsAnsible(debianRootfs);
                                                }
                                            }
                                            @Override public void onError(String error) { runMapsAnsible(debianRootfs); }
                                        });
                                    } else {
                                        // BASIC Edition or Companion Disabled
                                        finishInstallationSuccess();
                                    }
                                }
                                @Override public void onError(String error) { abortInstallation("Extraction Failed: " + error); }
                            });
                        }
                        @Override public void onError(String error) { abortInstallation("Download Failed: " + error); }
                    });
                };

                // Main Logic: Clean install or Reinstallation (Wipe first)?
                if (debianRootfs.exists() && debianRootfs.isDirectory()) {
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle(R.string.install_btn_reinstall)
                            .setMessage("This will wipe the current Debian system and download a fresh copy. Continue?")
                            .setPositiveButton("Yes", (dialog, which) -> {
                                btnFastInstall.setText("Wiping old system...");
                                btnFastInstall.setEnabled(false);
                                new Thread(() -> {
                                    try { Runtime.getRuntime().exec(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()}).waitFor(); } catch (Exception ignored) {}
                                    mainAct.runOnUiThread(executeDownload);
                                }).start();
                            })
                            .setNegativeButton("No", null)
                            .show();
                } else {
                    executeDownload.run(); // Clean install
                }
            });

            // =========================================================================
            // --- IIAB-NATIVE ARCHITECTURE ROADMAP: MAINTENANCE & RECOVERY ---
            // =========================================================================

            btnFastDelete.setOnClickListener(v -> {
                // CONFIRMATION DIALOG BEFORE NUKE
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Delete System?")
                        .setMessage("This will completely erase the IIAB system, databases, and all downloaded content. This action cannot be undone.")
                        .setPositiveButton("DELETE EVERYTHING", (dialog, which) -> {

                            mainAct.invalidateModuleStateTrust();
                            btnFastDelete.setEnabled(false);
                            Snackbar.make(getView(), "Starting native deletion...", Snackbar.LENGTH_SHORT).show();

                            // NATIVE DELETION: Java rm -rf
                            new Thread(() -> {
                                try {
                                    Process p = Runtime.getRuntime().exec(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                    p.waitFor();
                                    mainAct.runOnUiThread(this::updateDynamicButtons);
                                } catch (Exception e) {
                                    mainAct.runOnUiThread(() -> Snackbar.make(getView(), "Delete Failed: " + e.getMessage(), Snackbar.LENGTH_LONG).show());
                                }
                            }).start();

                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            if (btnAdvancedBackup != null) {
                btnAdvancedBackup.setOnClickListener(v -> {
                    btnAdvancedBackup.setEnabled(false);
                    Snackbar.make(v, "Creating native backup... (This takes a while)", Snackbar.LENGTH_LONG).show();

                    // NATIVE BACKUP: Using libtar and libgzip via host shell pipe
                    new Thread(() -> {
                        try {
                            String date = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
                            File backupFile = new File(backupsDir, "iiab_backup_" + date + ".tar.gz");

                            File staticTar = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libtar.so");
                            File staticGzip = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libgzip.so");
                            String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";
                            String gzipBin = staticGzip.exists() ? staticGzip.getAbsolutePath() : "gzip";

                            // We package the 'installed-rootfs' directory and pipe it through gzip
                            String cmd = tarBin + " -cf - -C " + iiabRootDir.getAbsolutePath() + " installed-rootfs | " + gzipBin + " > " + backupFile.getAbsolutePath();
                            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                            int exitCode = p.waitFor();

                            mainAct.runOnUiThread(() -> {
                                if (exitCode == 0) {
                                    Snackbar.make(getView(), "Backup Complete: " + backupFile.getName(), Snackbar.LENGTH_LONG).show();
                                } else {
                                    Snackbar.make(getView(), "Backup Failed with code: " + exitCode, Snackbar.LENGTH_LONG).show();
                                    if (backupFile.exists())
                                        backupFile.delete(); // Cleanup file if failed
                                }
                                updateDynamicButtons();
                            });
                        } catch (Exception e) {
                            mainAct.runOnUiThread(() -> {
                                Snackbar.make(getView(), "Backup Error: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
                                updateDynamicButtons();
                            });
                        }
                    }).start();
                });
            }

            if (btnAdvancedReset != null) {
                btnAdvancedReset.setOnClickListener(v -> {
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle(R.string.install_dialog_reset_title)
                            .setMessage(R.string.install_dialog_reset_msg)
                            .setPositiveButton(R.string.install_dialog_reset_confirm, (dialog, which) -> {
                                Snackbar.make(v, "Starting reset...", Snackbar.LENGTH_SHORT).show();
                                mainAct.invalidateModuleStateTrust();

                                // NATIVE RESET: Wipe everything
                                new Thread(() -> {
                                    try {
                                        Process p = Runtime.getRuntime().exec(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                        p.waitFor();
                                        mainAct.runOnUiThread(this::updateDynamicButtons);
                                    } catch (Exception e) {
                                        mainAct.runOnUiThread(() -> Snackbar.make(getView(), "Reset Failed: " + e.getMessage(), Snackbar.LENGTH_LONG).show());
                                    }
                                }).start();
                            })
                            .setNegativeButton(R.string.install_dialog_reset_cancel, null)
                            .show();
                });
            }

            // --- BACKUP DROP-DOWN MENU LOGIC (NATIVE) ---
            if (txtSelectBackupTitle != null) {
                txtSelectBackupTitle.setOnClickListener(v -> {
                    boolean isCollapsed = containerBackupList.getVisibility() == View.GONE;

                    if (isCollapsed) {
                        // Display menu
                        containerBackupList.setVisibility(View.VISIBLE);
                        txtSelectBackupTitle.setText(getString(R.string.install_adv_select_backup_open));

                        containerBackupList.removeAllViews();
                        selectedBackupFile = null;

                        // Pure Java reads the backups folder
                        File[] backups = backupsDir.listFiles((dir, name) -> name.endsWith(".tar.gz") || name.endsWith(".tar.xz"));

                        if (backups == null || backups.length == 0) {
                            TextView noBackups = new TextView(requireContext());
                            noBackups.setText(getString(R.string.install_msg_no_backups));
                            noBackups.setTextColor(Color.parseColor("#FF5555"));
                            containerBackupList.addView(noBackups);
                        } else {
                            // Sort in descending order by date
                            java.util.Arrays.sort(backups, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

                            android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(requireContext());
                            radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);

                            for (File b : backups) {
                                String filename = b.getName();
                                String size = String.format(java.util.Locale.US, "%.2f MB", b.length() / (1024.0 * 1024.0));
                                String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(b.lastModified()));

                                android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
                                rb.setText(getString(R.string.install_msg_backup_details, filename, size, date));
                                rb.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                                rb.setPadding(0, 16, 0, 16);
                                rb.setTag(filename);

                                rb.setOnClickListener(radioView -> {
                                    if (filename.equals(selectedBackupFile)) {
                                        radioGroup.clearCheck();
                                        selectedBackupFile = null;
                                    } else {
                                        selectedBackupFile = filename;
                                    }
                                    refreshRestoreButtonLogic();
                                });
                                radioGroup.addView(rb);
                            }
                            containerBackupList.addView(radioGroup);
                        }
                        refreshRestoreButtonLogic();
                    } else {
                        // Close menu
                        containerBackupList.setVisibility(View.GONE);
                        txtSelectBackupTitle.setText(getString(R.string.install_adv_select_backup));
                    }
                });
            }
        } // <- Closes CASE B
    }

    // =========================================================================================
    // NATIVE PIPELINE WORKERS
    // =========================================================================================

    private void editLocalVarsForMaps(File debianRootfs, InstallationPlanner.Tier tier) {
        File yamlFile = new File(debianRootfs, "etc/iiab/local_vars.yml");
        if (!yamlFile.exists()) return;

        try {
            StringBuilder content = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(yamlFile));
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append("\n");
            reader.close();

            String text = content.toString();

            text = text.replaceAll("(?m)^maps_install:\\s*.*", "maps_install: True");
            text = text.replaceAll("(?m)^maps_enabled:\\s*.*", "maps_enabled: True");
            text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: osm-z11");
            text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");

            if (tier == InstallationPlanner.Tier.STANDARD) {
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 7");
            } else {
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 8");
            }

            java.io.FileWriter writer = new java.io.FileWriter(yamlFile);
            writer.write(text);
            writer.close();
            android.util.Log.i(TAG, "YAML Maps variables updated successfully");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error editing YAML", e);
        }
    }

    private void downloadAndIndexKiwix(String zimFilename, File debianRootfs) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> btnFastInstall.setText("Preparing Kiwix..."));

        String zimUrl = "https://download.kiwix.org/zim/wikipedia/" + zimFilename;
        File libraryDir = new File(debianRootfs, "library/zims/content");
        if (!libraryDir.exists()) libraryDir.mkdirs();

        if (aria2Manager == null) aria2Manager = new Aria2Manager();

        aria2Manager.startDownload(requireContext(), zimUrl, new Aria2Manager.DownloadListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> btnFastInstall.setText("ZIM: " + percentage + "% | " + speed + "/s"));
                }
            }

            @Override
            public void onComplete(String downloadPath) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> btnFastInstall.setText("Indexing ZIM..."));

                File downloadedZim = new File(downloadPath, zimFilename);
                if (downloadedZim.exists()) downloadedZim.renameTo(new File(libraryDir, zimFilename));

                if (prootEngine == null) prootEngine = new PRootEngine();
                prootEngine.executeInContainer(requireContext(), debianRootfs.getAbsolutePath(), "iiab-make-kiwix-lib", new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) {
                        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).runOnUiThread(() -> ((MainActivity) getActivity()).addToLog("[Kiwix] " + line));
                    }
                    @Override public void onProcessExit(int exitCode) { runMapsAnsible(debianRootfs); }
                    @Override public void onError(String error) { runMapsAnsible(debianRootfs); }
                });
            }

            @Override
            public void onError(String error) {
                runMapsAnsible(debianRootfs);
            }
        });
    }

    private void runMapsAnsible(File debianRootfs) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> btnFastInstall.setText("Configuring Maps..."));

        if (prootEngine == null) prootEngine = new PRootEngine();
        String installCmd = "cd /opt/iiab/iiab && ./runrole maps";

        prootEngine.executeInContainer(requireContext(), debianRootfs.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override public void onOutputLine(String line) {
                if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).runOnUiThread(() -> ((MainActivity) getActivity()).addToLog("[Ansible] " + line));
            }
            @Override public void onProcessExit(int exitCode) { finishInstallationSuccess(); }
            @Override public void onError(String error) { finishInstallationSuccess(); }
        });
    }

    private void finishInstallationSuccess() {
        isDownloadingRootfs = false;
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                btnFastInstall.setText(R.string.install_btn_reinstall);
                btnFastInstall.setAlpha(1.0f);
                updateDynamicButtons();
                requestFreshLocalVarsSilently();
                if (getView() != null) Snackbar.make(getView(), "System Deployment Successful!", Snackbar.LENGTH_LONG).show();
            });
        }
    }

    private void abortInstallation(String message) {
        isDownloadingRootfs = false;
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                btnFastInstall.setText(R.string.install_btn_install);
                btnFastInstall.setAlpha(1.0f);
                updateDynamicButtons();
                if (getView() != null) Snackbar.make(getView(), message, Snackbar.LENGTH_LONG).show();
            });
        }
    }

    // --- AUXILIARY METHODS ---

    public void openTermuxAppInfo() {
        try {
            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            android.net.Uri uri = android.net.Uri.fromParts("package", requireContext().getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error opening App Info", e);
        }
    }

    private void pollForBackupsJson(File jsonFile, int attemptsLeft, int delayMs) {
        if (attemptsLeft <= 0) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (txtBackupStatus != null) {
                        txtBackupStatus.setText(getString(R.string.install_msg_no_backups));
                        txtBackupStatus.setTextColor(Color.parseColor("#FF5555"));
                    }
                    selectedBackupFile = null;
                    refreshRestoreButtonLogic();
                });
            }
            return;
        }

        if (jsonFile.exists() && jsonFile.length() > 0) {
            try {
                StringBuilder text = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                String line;
                while ((line = br.readLine()) != null) text.append(line);
                br.close();

                JSONObject backupsData = new JSONObject(text.toString());
                org.json.JSONArray backupsArray = backupsData.optJSONArray("backups");
                String defaultNa = getString(R.string.install_msg_backup_na);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        containerBackupList.removeAllViews();
                        selectedBackupFile = null;

                        if (backupsArray == null || backupsArray.length() == 0) {
                            TextView noBackups = new TextView(requireContext());
                            noBackups.setText(getString(R.string.install_msg_no_backups));
                            noBackups.setTextColor(Color.parseColor("#FF5555"));
                            containerBackupList.addView(noBackups);
                        } else {
                            android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(requireContext());
                            radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);

                            for (int i = 0; i < backupsArray.length(); i++) {
                                JSONObject backupObj = backupsArray.optJSONObject(i);
                                if (backupObj == null) continue;

                                String filename = backupObj.optString("filename", "");
                                String size = backupObj.optString("size", defaultNa);
                                String date = backupObj.optString("date", defaultNa);

                                android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
                                rb.setText(getString(R.string.install_msg_backup_details, filename, size, date));
                                rb.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                                rb.setPadding(0, 16, 0, 16);
                                rb.setTag(filename);

                                rb.setOnClickListener(v -> {
                                    if (filename.equals(selectedBackupFile)) {
                                        radioGroup.clearCheck();
                                        selectedBackupFile = null;
                                    } else {
                                        selectedBackupFile = filename;
                                    }
                                    refreshRestoreButtonLogic();
                                });

                                radioGroup.addView(rb);
                            }
                            containerBackupList.addView(radioGroup);
                        }
                        refreshRestoreButtonLogic();
                    });
                }
                return;
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error parsing backups JSON", e);
            }
        }

        new Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                pollForBackupsJson(jsonFile, attemptsLeft - 1, delayMs), delayMs);
    }

    private void refreshRestoreButtonLogic() {
        MainActivity mainAct = (MainActivity) getActivity();
        if (mainAct == null || btnAdvancedRestore == null) return;

        if (mainAct.isServerAlive) {
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v ->
                    Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show());
            return;
        }

        if (selectedBackupFile == null) {
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v ->
                    Snackbar.make(v, R.string.install_msg_select_backup_first, Snackbar.LENGTH_LONG).show());
        } else {
            btnAdvancedRestore.setAlpha(1.0f);
            btnAdvancedRestore.setOnClickListener(v -> {
                String startingMsg = getString(R.string.install_msg_restore_starting, selectedBackupFile);
                Snackbar.make(v, startingMsg, Snackbar.LENGTH_SHORT).show();

                mainAct.invalidateModuleStateTrust();

                File backupsDir = new File(requireContext().getFilesDir(), "rootfs/backups");
                File backupFile = new File(backupsDir, selectedBackupFile);

                if (!backupFile.exists()) {
                    Snackbar.make(v, "Backup file missing!", Snackbar.LENGTH_SHORT).show();
                    return;
                }

                btnAdvancedRestore.setEnabled(false);
                btnAdvancedRestore.setText("Restoring...");

                File iiabRootDir = new File(requireContext().getFilesDir(), "rootfs");

                TarExtractor tarExtractor = new TarExtractor();
                tarExtractor.startExtraction(requireContext(), backupFile.getAbsolutePath(), iiabRootDir.getAbsolutePath(), new TarExtractor.ExtractionListener() {
                    @Override
                    public void onComplete(String destDir) {
                        mainAct.runOnUiThread(() -> {
                            btnAdvancedRestore.setEnabled(true);
                            btnAdvancedRestore.setText(getString(R.string.install_btn_restore));
                            Snackbar.make(getView(), "Restore Complete!", Snackbar.LENGTH_LONG).show();
                            updateDynamicButtons();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mainAct.runOnUiThread(() -> {
                            btnAdvancedRestore.setEnabled(true);
                            btnAdvancedRestore.setText(getString(R.string.install_btn_restore));
                            Snackbar.make(getView(), getString(R.string.install_msg_restore_failed) + " " + error, Snackbar.LENGTH_LONG).show();
                            updateDynamicButtons();
                        });
                    }
                });
            });
        }
    }

    private void refreshDashboardLeds(MainActivity mainAct) {
        if (mainAct == null) return;

        boolean isDevModeOn = false;
        try {
            isDevModeOn = android.provider.Settings.Global.getInt(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Could not check Developer Mode status", e);
        }
        ledDevMode.setBackgroundResource(isDevModeOn ? R.drawable.led_on_green : R.drawable.led_off);
    }

    private void updateUiState(boolean isConnected) {
        btnAdbAction.setEnabled(true);
        if (isConnected) {
            ledAdbStatus.setBackgroundResource(R.drawable.led_on_green);
            txtAdbLedLabel.setText(getString(R.string.adb_status_connected));
            txtAdbLedLabel.setTextColor(Color.parseColor("#4CAF50"));
            btnAdbAction.setText(getString(R.string.adb_btn_disconnect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));

            txtDcpr.setText(android.text.Html.fromHtml("Child Process<br><i>(Checking...)</i>", android.text.Html.FROM_HTML_MODE_COMPACT));
            txtPpk.setText(android.text.Html.fromHtml("PPK Limit<br><i>(Checking...)</i>", android.text.Html.FROM_HTML_MODE_COMPACT));
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);

        } else {
            ledAdbStatus.setBackgroundResource(R.drawable.led_off);
            txtAdbLedLabel.setText(getString(R.string.adb_status_offline));
            txtAdbLedLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
            btnAdbAction.setText(getString(R.string.adb_btn_connect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3")));

            txtDcpr.setText("Child Process\n(--)");
            txtPpk.setText("PPK Limit\n(--)");
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

        if (multicastLock != null && !multicastLock.isHeld()) {
            android.util.Log.d(TAG, "Acquiring MulticastLock to wake up mDNS listener...");
            multicastLock.acquire();
        }

        connectDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_CONNECT);
        pairingDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_PAIRING);

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error starting NsdManager", e);
            resetScanState();
            return;
        }

        if (!isSilentScan) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isScanning && !isConnectedToAdb) {
                    openDeveloperOptions();
                }
            }, 4000);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::checkIfScanTimedOut, 90000);
    }

    private int getDynamicAdbPort(int fallbackPort) {
        try {
            Process process = Runtime.getRuntime().exec("getprop service.adb.tls.port");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String portStr = reader.readLine();
            reader.close();
            if (portStr != null && !portStr.trim().isEmpty()) {
                int port = Integer.parseInt(portStr.trim());
                if (port > 0) return port;
            }
        } catch (Exception ignored) {}
        return fallbackPort;
    }

    private void attemptFastConnection(int port) {
        if (isAttemptingFastConnect) return;
        isAttemptingFastConnect = true;

        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            boolean connected = false;
            IIABAdbManager adbManager = IIABAdbManager.getInstance(appContext);

            for (int i = 0; i < 6; i++) {
                try {
                    int currentPort = getDynamicAdbPort(port);
                    android.util.Log.d(TAG, "Fast connect attempt " + (i + 1) + " on port " + currentPort);
                    adbManager.connect("127.0.0.1", currentPort);
                    connected = true;
                    break;
                } catch (Exception e) {
                    android.util.Log.w(TAG, "Fast connect failed. Retrying...", e);
                    try {
                        adbManager.disconnect();
                        Thread.sleep(800);
                    } catch (Exception ignored) {}
                }
            }

            if (connected) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopDiscovery();
                    isConnectedToAdb = true;
                    if (btnAdbAction != null) {
                        btnAdbAction.setText(getString(R.string.adb_status_connected));
                    }
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

    private String getLocalWifiIp() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                return String.format(java.util.Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
            }
        }
        return "127.0.0.1";
    }

    private void resolveService(android.net.nsd.NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new android.net.nsd.NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(android.net.nsd.NsdServiceInfo serviceInfo, int errorCode) {}

            @Override
            public void onServiceResolved(android.net.nsd.NsdServiceInfo serviceInfo) {
                int port = serviceInfo.getPort();
                String type = serviceInfo.getServiceType();
                String hostIp = serviceInfo.getHost().getHostAddress();
                String myIp = getLocalWifiIp();

                if (hostIp != null && !hostIp.equals(myIp) && !hostIp.equals("127.0.0.1")) {
                    android.util.Log.w(TAG, "Ignoring external ADB service from: " + hostIp);
                    return;
                }

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

    private void attemptFastConnection(String hostIp, int port) {
        if (isAttemptingFastConnect) return;
        isAttemptingFastConnect = true;

        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            boolean connected = false;
            IIABAdbManager adbManager = IIABAdbManager.getInstance(appContext);

            for (int i = 0; i < 6; i++) {
                try {
                    android.util.Log.d(TAG, "Fast connect attempt " + (i + 1) + " on " + hostIp + ":" + port);
                    adbManager.connect(hostIp, port);
                    connected = true;
                    break;
                } catch (Exception e) {
                    android.util.Log.w(TAG, "Fast connect failed. Retrying...", e);
                    try {
                        adbManager.disconnect();
                        Thread.sleep(600);
                    } catch (Exception ignored) {}
                }
            }

            if (connected) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopDiscovery();
                    isConnectedToAdb = true;
                    if (btnAdbAction != null) {
                        btnAdbAction.setText(getString(R.string.adb_status_connected));
                    }
                    updateUiState(true);
                });

                adbManager.startCpuMonitor(appContext);
                adbManager.checkSystemRestrictions(appContext);
            } else {
                isAttemptingFastConnect = false;
            }
        }).start();
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
            if (multicastLock != null && multicastLock.isHeld()) {
                android.util.Log.d(TAG, "Releasing MulticastLock...");
                multicastLock.release();
            }
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
        xAxis.setGridColor(Color.parseColor("#333333"));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        YAxis leftAxis = cpuChart.getAxisLeft();
        leftAxis.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
        leftAxis.setAxisMaximum(100f);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#333333"));

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
            newSet.setColor(Color.parseColor("#4CAF50"));
            newSet.setLineWidth(2f);
            newSet.setDrawCircles(false);
            newSet.setDrawValues(false);
            newSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            newSet.setDrawFilled(true);
            newSet.setFillColor(Color.parseColor("#4CAF50"));
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (getActivity() != null && getActivity().isChangingConfigurations()) {
            return;
        }

        if (aria2Manager != null && isDownloadingRootfs) {
            aria2Manager.stopDownload();
            isDownloadingRootfs = false;
        }
    }
    // =========================================================================
    // INSTALLATION PLANNER LOGIC
    // =========================================================================

    private void setupPlannerListeners() {
        // --- Visual hint to "break the seal" ---
        // Paint 'Basic' green but dimmed to invite interaction, while logically keeping selectedTier = null.
        btnTierBasic.setAlpha(0.5f);
        btnTierBasic.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008000")));

        btnTierStandard.setAlpha(0.5f);
        btnTierStandard.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));

        btnTierFull.setAlpha(0.5f);
        btnTierFull.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));

        View.OnClickListener tierClickListener = v -> {
            // Restore alpha upon interaction
            btnTierBasic.setAlpha(1.0f);
            btnTierStandard.setAlpha(1.0f);
            btnTierFull.setAlpha(1.0f);

            // Reset active colors
            btnTierBasic.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));
            btnTierStandard.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));
            btnTierFull.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));

            // Highlight the clicked button
            v.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008000")));

            if (v.getId() == R.id.btn_tier_basic) selectedTier = InstallationPlanner.Tier.BASIC;
            else if (v.getId() == R.id.btn_tier_standard) selectedTier = InstallationPlanner.Tier.STANDARD;
            else if (v.getId() == R.id.btn_tier_full) selectedTier = InstallationPlanner.Tier.FULL;

            overrideKiwixVariant = null;
            recalculateProjection();
        };

        btnTierBasic.setOnClickListener(tierClickListener);
        btnTierStandard.setOnClickListener(tierClickListener);
        btnTierFull.setOnClickListener(tierClickListener);

        chkCompanionData.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                btnKiwixSettings.setColorFilter(ContextCompat.getColor(requireContext(), R.color.colorAccent));
            } else {
                btnKiwixSettings.setColorFilter(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
            }
            recalculateProjection();
        });

        // Initial calculation
        recalculateProjection();
    }

    private void recalculateProjection() {
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        // Fallback to system language if no manual override is set
        String targetLang = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", "en");

        // We still evaluate BASIC under the hood to get the resolved language, but we will zero out the numbers later if nothing is selected
        InstallationPlanner.Tier evalTier = (selectedTier != null) ? selectedTier : InstallationPlanner.Tier.BASIC;

        InstallationPlanner.calculateProjectedSize(requireContext(), evalTier, chkCompanionData.isChecked(), targetLang, overrideKiwixVariant, new InstallationPlanner.PlanResultListener() {
            @Override
            public void onCalculated(InstallationPlanner.StorageProjection projection) {
                if (!isAdded()) return;

                File path = android.os.Environment.getDataDirectory();
                double freeSpaceGb = path.getFreeSpace() / (1024.0 * 1024.0 * 1024.0);
                double totalSpaceGb = path.getTotalSpace() / (1024.0 * 1024.0 * 1024.0);
                double usedSpaceGb = totalSpaceGb - freeSpaceGb;

                // 1. FILTER: If no tier is selected, projected values are exactly 0.0
                double pOs = (selectedTier == null) ? 0.0 : projection.osSize;
                double pMaps = (selectedTier == null) ? 0.0 : projection.mapsSize;
                double pKiwix = (selectedTier == null) ? 0.0 : projection.kiwixSize;
                double pTotal = pOs + pMaps + pKiwix;

                // 2. SAFETY BUFFER
                double availableForInstall = freeSpaceGb - 5.0;
                isStorageSafe = pTotal <= availableForInstall;

                // 3. UPDATE LEGEND
                if (txtLegendIiab != null) txtLegendIiab.setText(String.format(java.util.Locale.US, "%.1fG", pOs));
                if (txtLegendMaps != null) txtLegendMaps.setText(String.format(java.util.Locale.US, "%.1fG", pMaps));
                if (txtLegendKiwix != null) txtLegendKiwix.setText(String.format(java.util.Locale.US, "%.1fG", pKiwix));

                // Wikipedia dynamic label logic
                TextView lblWiki = getView().findViewById(R.id.txt_legend_kiwix).getRootView().findViewWithTag("label_kiwix");
                if (lblWiki == null) {
                    ViewGroup parent = (ViewGroup) txtLegendKiwix.getParent();
                    lblWiki = (TextView) parent.getChildAt(1);
                }
                if (lblWiki != null) {
                    if (chkCompanionData.isChecked()) {
                        lblWiki.setText("Wikipedia (" + projection.resolvedLang.toUpperCase() + ")");
                        lblWiki.setTextColor(Color.parseColor("#2196F3")); // Active Blue
                    } else {
                        lblWiki.setText("Wikipedia");
                        lblWiki.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary)); // Inactive Grey
                    }
                }

                double projectedFreeSpace = freeSpaceGb - pTotal;
                if (txtLegendFree != null) {
                    if (isStorageSafe) {
                        txtLegendFree.setText(String.format(java.util.Locale.US, "%.1fG", projectedFreeSpace));
                        txtLegendFree.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_inverted));
                    } else {
                        txtLegendFree.setText("OVERLOAD");
                        txtLegendFree.setTextColor(Color.parseColor("#F44336")); // Red Overload
                    }
                }

                // 4. UPDATE GAUGE
                if (storageGauge != null) {
                    List<MultiResourceGaugeView.Segment> segments = new ArrayList<>();

                    float otherUsedPct = (totalSpaceGb > 0) ? (float) (usedSpaceGb / totalSpaceGb) * 100f : 0f;
                    float osPct = (totalSpaceGb > 0) ? (float) (pOs / totalSpaceGb) * 100f : 0f;
                    float mapsPct = (totalSpaceGb > 0) ? (float) (pMaps / totalSpaceGb) * 100f : 0f;
                    float kiwixPct = (totalSpaceGb > 0) ? (float) (pKiwix / totalSpaceGb) * 100f : 0f;

                    float totalDrawn = 0f;

                    // 1st Segment: Space already used by Android - WHITE
                    if (otherUsedPct > 0) {
                        float draw = Math.min(otherUsedPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.WHITE));
                        totalDrawn += draw;
                    }
                    // Next segments ONLY draw if a tier is actively selected (since pOs, pMaps, pKiwix would be > 0)
                    if (osPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(osPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.parseColor("#00FFFF")));
                        totalDrawn += draw;
                    }
                    if (mapsPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(mapsPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.parseColor("#FF9800")));
                        totalDrawn += draw;
                    }
                    if (kiwixPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(kiwixPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.parseColor("#008000")));
                        totalDrawn += draw;
                    }

                    // If no tier is selected, keep center text white
                    int centerColor = (selectedTier == null || isStorageSafe) ?
                            ContextCompat.getColor(requireContext(), R.color.dash_text_inverted) :
                            Color.parseColor("#F44336");
                    String centerTextValue = String.format(java.util.Locale.US, "%.1fG", pTotal);

                    storageGauge.updateData(segments, centerTextValue, centerColor, "Projected", "Storage");
                }

                // 5. MASTER DELEGATION
                // Pass the baton entirely to the dynamic UI updater to prevent conflicting event listeners
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> updateDynamicButtons());
                }
            }

            @Override
            public void onError(String error) {
                if (isAdded() && txtLegendFree != null) {
                    txtLegendFree.setText("Error");
                }
            }
        });
    }

    private void showKiwixSettingsDialog() {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(requireContext()).create();
        View view = getLayoutInflater().inflate(R.layout.dialog_install_planner_settings, null);
        dialog.setView(view);

        android.widget.Spinner spinnerLang = view.findViewById(R.id.spinner_kiwix_lang);
        Button btnWipe = view.findViewById(R.id.btn_wipe_cache);
        Button btnSelect = view.findViewById(R.id.btn_select_variant);
        android.widget.RadioGroup rgVariants = view.findViewById(R.id.rg_kiwix_variants);

        btnWipe.setOnClickListener(v -> {
            InstallationPlanner.wipeCache(requireContext());
            rgVariants.removeAllViews();
            spinnerLang.setAdapter(null);
            overrideKiwixVariant = null; // Clear manual state
            Snackbar.make(getView(), "Kiwix Cache wiped successfully", Snackbar.LENGTH_SHORT).show();
        });

        InstallationPlanner.getOrFetchCatalog(requireContext(), new InstallationPlanner.CacheListener() {
            @Override
            public void onReady(JSONObject catalog) {
                if (!isAdded()) return;

                List<String> langKeys = new ArrayList<>();
                java.util.Iterator<String> keys = catalog.keys();
                while (keys.hasNext()) langKeys.add(keys.next());
                java.util.Collections.sort(langKeys);

                List<String> displayNames = new ArrayList<>();
                int selectedIndex = 0;

                android.content.SharedPreferences prefs = requireContext().getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                String currentTarget = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", "en");

                for (int i = 0; i < langKeys.size(); i++) {
                    String code = langKeys.get(i);
                    java.util.Locale loc = new java.util.Locale(code);
                    String name = loc.getDisplayLanguage(loc);
                    name = name.substring(0, 1).toUpperCase() + name.substring(1);
                    displayNames.add(name + " / " + loc.getDisplayLanguage(java.util.Locale.US));
                    if (code.equals(currentTarget)) selectedIndex = i;
                }

                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, displayNames);
                spinnerLang.setAdapter(adapter);
                spinnerLang.setSelection(selectedIndex);

                spinnerLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                        String selectedCode = langKeys.get(position);
                        rgVariants.removeAllViews(); // Clean list

                        JSONObject variants = catalog.optJSONObject(selectedCode);
                        if (variants != null) {
                            java.util.Iterator<String> vKeys = variants.keys();
                            while (vKeys.hasNext()) {
                                String vk = vKeys.next();
                                JSONObject vData = variants.optJSONObject(vk);
                                double size = (vData != null) ? vData.optDouble("size", 0.0) : 0.0;

                                // Create RadioButtons dynamically
                                android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
                                rb.setId(View.generateViewId());
                                rb.setText(String.format(java.util.Locale.US, "%-22s %5.1f GB", vk, size));
                                rb.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                                rb.setTypeface(Typeface.MONOSPACE);
                                rb.setTag(vk);
                                rgVariants.addView(rb);

                                // Check if it was previously selected manually
                                if (vk.equals(overrideKiwixVariant)) rb.setChecked(true);
                            }
                        }
                    }
                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

                // SELECT BUTTON LOGIC
                btnSelect.setOnClickListener(v -> {
                    int checkedId = rgVariants.getCheckedRadioButtonId();
                    if (checkedId != -1) {
                        android.widget.RadioButton rb = rgVariants.findViewById(checkedId);
                        overrideKiwixVariant = (String) rb.getTag();
                        overrideKiwixLang = langKeys.get(spinnerLang.getSelectedItemPosition());

                        recalculateProjection(); // Re-evaluate entire UI and Safety Lock
                        dialog.dismiss();
                    } else {
                        Snackbar.make(getView(), "Please select a ZIM variant from the list", Snackbar.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (isAdded()) Snackbar.make(getView(), "Error loading catalog: " + error, Snackbar.LENGTH_LONG).show();
            }
        });

        dialog.show();
    }
    /**
     * Pings Google's captive portal generation URL to verify actual Internet access.
     * Updates LED to Green if online, or Red if offline.
     */
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
                // 204 means successful connection
                hasInternet = (conn.getResponseCode() == 204 || conn.getResponseCode() == 200);
            } catch (Exception e) {
                hasInternet = false;
            }

            final boolean isOnline = hasInternet;
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (ledInternet != null) {
                        if (isOnline) {
                            // Internet available: Green LED
                            ledInternet.setBackgroundResource(R.drawable.led_on_green);
                            ledInternet.setBackgroundTintList(null); // Clear any existing tint
                        } else {
                            // Internet unavailable: Red LED
                            // We use the 'off' drawable as a base shape and tint it red
                            ledInternet.setBackgroundResource(R.drawable.led_off);
                            ledInternet.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336"))); // RED
                        }
                    }
                });
            }
        }).start();
    }
}