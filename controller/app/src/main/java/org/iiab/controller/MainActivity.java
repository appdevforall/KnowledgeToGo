/*
 ============================================================================
 Name        : MainActivity.java
 Contributors: IIAB Project
 Copyright (c) 2026 IIAB Project
 Description : Main Activity
 ============================================================================
 */

package org.iiab.controller;

import android.Manifest;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.viewpager2.widget.ViewPager2;

import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Proxy;
import java.net.InetSocketAddress;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "IIAB-MainActivity";
    public Preferences prefs;
    private ImageButton themeToggle;
    private ImageButton btnSettings;
    private android.widget.ImageView headerIcon;

    private long updateDownloadId = -1;
    private long lastUpdateCheckTime = 0;

    // Tabs UI
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private TextView versionFooter;
    public boolean isServerAlive = false;
    public boolean isNegotiating = false;
    public DashboardFragment.SystemState currentSystemState = DashboardFragment.SystemState.NONE;
    public boolean isProxyDegraded = false;
    public Boolean targetServerState = null;
    public String serverTransitionText = "";
    public UsageFragment usageFragment;

    public void setUsageFragment(UsageFragment fragment) {
        this.usageFragment = fragment;
    }

    private final Handler timeoutHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private boolean isWifiActive = false;
    private boolean isHotspotActive = false;
    private String currentTargetUrl = null;
    private long pulseStartTime = 0;

    private ActivityResultLauncher<Intent> vpnPermissionLauncher;
    private ActivityResultLauncher<String[]> requestPermissionsLauncher;
    private ActivityResultLauncher<Intent> batteryOptLauncher;

    public boolean isReadingLogs = false;
    private final Handler sizeUpdateHandler = new Handler();
    private Runnable sizeUpdateRunnable;

    // Variables for adaptive localhost server check
    private final Handler serverCheckHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable serverCheckRunnable;
    private static final int CHECK_INTERVAL_MS = 3000;
    public PRootEngine serverEngine;
    private float currentTerminalFontSize = 24f;

    // Load native C++ engine
    static {
        System.loadLibrary("termux");
    }

    /**
     * Dummy method to satisfy legacy fragments.
     * Since we are now a monolithic app with an embedded PRoot environment,
     * the host is always "installed".
     */
    public boolean isTermuxInstalled() {
        return true;
    }

    // New variables for the ninja terminal
    private com.termux.view.TerminalView terminalView;
    private com.termux.terminal.TerminalSession terminalSession;
    private com.google.android.material.bottomsheet.BottomSheetBehavior<View> bottomSheetBehavior;

    public void invalidateModuleStateTrust() {
        getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_module_state_trusted", false)
                .apply();
    }

    public boolean isModuleStateTrusted() {
        return getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_module_state_trusted", true);
    }

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (IIABWatchdog.ACTION_LOG_MESSAGE.equals(action)) {
                String message = intent.getStringExtra(IIABWatchdog.EXTRA_MESSAGE);
                addToLog(message);
                if (usageFragment != null) usageFragment.updateLogSizeUI();
            } else if (WatchdogService.ACTION_STATE_STARTED.equals(action)) {
                // Keep the visual sync pulse alive if the UI reloads while protected
                if (usageFragment != null) usageFragment.startFusionPulse();
            } else if (WatchdogService.ACTION_STATE_STOPPED.equals(action)) {
                // Service is down! Give it a visual margin, then stop the exit pulse.
                new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (usageFragment != null) usageFragment.finalizeExitPulse();
                }, 1500);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Intercept launch and redirect to Setup Wizard if first time
        SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        if (!internalPrefs.getBoolean(getString(R.string.pref_key_setup_complete), false)) {
            try {
                startActivity(new Intent(this, SetupActivity.class));
                finish();
                return; // We stop the execution of MainActivity right here
            } catch (android.content.ActivityNotFoundException e) {
                android.util.Log.w(TAG, "SetupActivity not found. Skipping initial setup.");
                internalPrefs.edit().putBoolean(getString(R.string.pref_key_setup_complete), true).apply();
            }
        }

        prefs = new Preferences(this);
        setContentView(R.layout.main);

        // --- START TABS & VIEWPAGER ---
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        MainPagerAdapter pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText(R.string.tab_status);
                    break;
                case 1:
                    tab.setText(R.string.tab_usage);
                    break;
                case 2:
                    tab.setText(R.string.tab_deploy);
                    break;
                case 3:
                    tab.setText(R.string.tab_share);
                    break;
            }
        }).attach();

        // --- START: EASTER EGG & OTA LOGIC ---
        versionFooter = findViewById(R.id.version_text);
        setVersionFooter();

        // Configure hidden bottom sheet
        View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
        terminalView = findViewById(R.id.terminal_view);
        bottomSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
        bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);

        // 3-second Ninja trigger & OTA Updater
        versionFooter.setOnTouchListener(new View.OnTouchListener() {
            private final Handler handler = new Handler(android.os.Looper.getMainLooper());
            private final Runnable longPressRunnable = () -> {
                try {
                    // 3 seconds reached! Expand terminal.
                    vibrateDevice();

                    // --- TERMINAL RESET NUKE ---
                    terminalSession = null;
                    setupTerminalSession();

                    // 1. Force the view to be VISIBLE before expanding
                    View targetSheet = findViewById(R.id.terminal_bottom_sheet);
                    if (targetSheet.getVisibility() != View.VISIBLE) {
                        targetSheet.setVisibility(View.VISIBLE);
                    }

                    // 2. Bring view to front
                    targetSheet.bringToFront();
                    targetSheet.requestLayout();

                    // 3. Change to EXPANDED (100% screen)
                    bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                    terminalView.requestFocus();

                } catch (Throwable t) {
                    // CATCH ABSOLUTELY EVERYTHING (Even native JNI crashes)
                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Terminal Spawn Crash")
                            .setMessage(android.util.Log.getStackTraceString(t))
                            .setPositiveButton("OK", null)
                            .show();
                }
            };
            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartTime = System.currentTimeMillis();
                        handler.postDelayed(longPressRunnable, 3000);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);

                        // --- THE UPDATER (OTA LOGIC) ---
                        // If released early and it was a quick tap (<500ms), trigger normal OTA update
                        if (System.currentTimeMillis() - touchStartTime < 500) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUpdateCheckTime < 10000) {
                                Toast.makeText(MainActivity.this, R.string.ota_toast_cooldown, Toast.LENGTH_SHORT).show();
                            } else {
                                lastUpdateCheckTime = currentTime;
                                checkForUpdates(true);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        // Check for version with 10s cooldown span
        versionFooter.setOnClickListener(v -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUpdateCheckTime < 10000) {
                Toast.makeText(this, R.string.ota_toast_cooldown, Toast.LENGTH_SHORT).show();
                return;
            }
            lastUpdateCheckTime = currentTime;
            checkForUpdates(true);
        });

        viewPager.setCurrentItem(0, false);

        // 1. Initialize Result Launchers
        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && prefs.getEnable()) {
                        connectVpn();
                    }
                    BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
                }
        );

        batteryOptLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "Returned from the battery settings screen");
                    BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
                }
        );

        requestPermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                        if (entry.getKey().equals(Manifest.permission.POST_NOTIFICATIONS)) {
                            addToLog(getString(entry.getValue() ? R.string.notif_perm_granted : R.string.notif_perm_denied));
                        }
                    }
                    prepareVpn();
                }
        );

        themeToggle = findViewById(R.id.theme_toggle);
        btnSettings = findViewById(R.id.btn_settings);
        headerIcon = findViewById(R.id.header_icon);
        ImageButton btnShareQr = findViewById(R.id.btn_share_qr);

        // Listeners
        themeToggle.setOnClickListener(v -> toggleTheme());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SetupActivity.class)));

        // --- QR Share Button Logic ---
        btnShareQr.setOnClickListener(v -> {
            if (!isServerAlive) {
                // Rule 1: Server must be running
                Snackbar.make(findViewById(android.R.id.content), R.string.qr_error_no_server, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (!isWifiActive && !isHotspotActive) {
                // Rule 2: At least one network must be active
                Snackbar.make(findViewById(android.R.id.content), R.string.qr_error_no_network, Snackbar.LENGTH_LONG).show();
                return;
            }

            // Launch the new QrActivity
            startActivity(new Intent(MainActivity.this, QrActivity.class));
        });

        applySavedTheme();
        updateUI();

        addToLog(getString(R.string.app_started));
        checkForUpdates(false);

        sizeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (usageFragment != null && usageFragment.isAdded())
                    usageFragment.updateLogSizeUI();
                sizeUpdateHandler.postDelayed(this, 10000);
            }
        };

        serverCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkServerStatus();
                updateConnectivityStatus(); // Check Wi-Fi & Hotspot states
                serverCheckHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        serverCheckHandler.post(serverCheckRunnable);
    }

    private void showBatterySnackbar() {
        View rootView = findViewById(android.R.id.content);
        Snackbar.make(rootView, R.string.battery_opt_denied, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.fix_action, v -> BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher))
                .show();
    }

    private void initiatePermissionChain() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // ADDED CAMERA PERMISSION REQUEST
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }

        if (!permissions.isEmpty()) {
            requestPermissionsLauncher.launch(permissions.toArray(new String[0]));
        } else {
            prepareVpn();
        }
    }

    private boolean pingUrl(String urlStr, boolean useProxy) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn;

            if (useProxy) {
                // We routed the request directly to the app's SOCKS proxy
                int socksPort = prefs.getSocksPort(); // generally 1080
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
                conn = (HttpURLConnection) url.openConnection(proxy);
            } else {
                // Normal request (for localhost)
                conn = (HttpURLConnection) url.openConnection();
            }

            conn.setUseCaches(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            return (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400);
        } catch (Exception e) {
            return false;
        }
    }

    private void runNegotiationSequence() {
        isNegotiating = true;
        runOnUiThread(() -> {
            updateUIColorsAndVisibility(); // We forced an immediate visual update
        });

        new Thread(() -> {
            boolean boxAlive = false;

            // Attempt 1 (0 seconds)
            boxAlive = pingUrl("http://box/home", true);

            // Attempt 2 (At 2 seconds)
            if (!boxAlive) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                boxAlive = pingUrl("http://box/home", true);
            }

            // Attempt 3 (At 3 seconds)
            if (!boxAlive) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                boxAlive = pingUrl("http://box/home", true);
            }

            // We validate if localhost serves as a fallback.
            boolean localAlive = pingUrl("http://localhost:8085/home", false);

            // We evaluate the results
            isNegotiating = false;
            isServerAlive = boxAlive || localAlive;

            // If VPN is ON but box/proxy is dead, the tunnel is degraded (Orange).
            if (prefs.getEnable()) {
                isProxyDegraded = !boxAlive;
            } else {
                isProxyDegraded = false;
            }

            if (boxAlive) {
                currentTargetUrl = "http://box/home";
            } else if (localAlive) {
                currentTargetUrl = "http://localhost:8085/home";
            } else {
                currentTargetUrl = null;
            }

            runOnUiThread(this::updateUIColorsAndVisibility);
        }).start();
    }

    private void prepareVpn() {
//        Intent intent = VpnService.prepare(MainActivity.this);
//        if (intent != null) {
//            vpnPermissionLauncher.launch(intent);
//        } else {
//            if (prefs.getEnable()) connectVpn();
//            BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
//        }
        BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
    }

    public void startLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
        sizeUpdateHandler.post(sizeUpdateRunnable);
    }

    public void stopLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
    }

    private void connectVpn() {
        addToLog(getString(R.string.vpn_permission_granted));
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            unregisterReceiver(downloadReceiver);
        } catch (IllegalArgumentException e) {
            // Ignore if it wasn't registered
        }

        stopLogSizeUpdates();
        serverCheckHandler.removeCallbacks(serverCheckRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register download listener
        IntentFilter filter = new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        //  Check permissions status
        updateHeaderIconsOpacity();

        // Check battery status whenever returning to the app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Log.d(TAG, "onResume: Battery still optimized, showing warning");
                showBatterySnackbar();
            }
        }
        updateConnectivityStatus(); // Force instant UI refresh when returning to app

        if (usageFragment != null && usageFragment.isLogVisible()) {
            startLogSizeUpdates();
        }
        serverCheckHandler.removeCallbacks(serverCheckRunnable);
        serverCheckHandler.post(serverCheckRunnable);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void toggleTheme() {
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        int currentMode = AppCompatDelegate.getDefaultNightMode();
        int nextMode = (currentMode == AppCompatDelegate.MODE_NIGHT_NO) ? AppCompatDelegate.MODE_NIGHT_YES :
                (currentMode == AppCompatDelegate.MODE_NIGHT_YES) ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : AppCompatDelegate.MODE_NIGHT_NO;
        sharedPref.edit().putInt("ui_mode", nextMode).apply();
        AppCompatDelegate.setDefaultNightMode(nextMode);
        updateThemeToggleButton(nextMode);
    }

    private void applySavedTheme() {
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        int savedMode = sharedPref.getInt("ui_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(savedMode);
        updateThemeToggleButton(savedMode);
    }

    private void updateThemeToggleButton(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_NO)
            themeToggle.setImageResource(R.drawable.ic_theme_dark);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES)
            themeToggle.setImageResource(R.drawable.ic_theme_light);
        else themeToggle.setImageResource(R.drawable.ic_theme_system);
    }


    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(IIABWatchdog.ACTION_LOG_MESSAGE);
        filter.addAction(WatchdogService.ACTION_STATE_STARTED);
        filter.addAction(WatchdogService.ACTION_STATE_STOPPED);

        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(logReceiver);
        } catch (Exception e) {
        }
        stopLogSizeUpdates();
    }

    @Override
    public void onClick(View view) {
        // Delegated
    }

    public void handleControlClick() {
        if (!isServerAlive) {
            Snackbar.make(findViewById(android.R.id.content), R.string.qr_error_no_server, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (prefs.getEnable()) {
            BiometricHelper.prompt(this,
                    getString(R.string.auth_required_title),
                    getString(R.string.auth_required_subtitle),
                    () -> {
                        addToLog(getString(R.string.auth_success_disconnect));
                        toggleService(true);
                    });
        } else {
            if (BiometricHelper.isDeviceSecure(this)) {
                addToLog(getString(R.string.user_initiated_conn));
                toggleService(false);
            } else {
                BiometricHelper.showEnrollmentDialog(this);
            }
        }
    }

    public void handleBrowseContentClick(View v) {
        if (!isServerAlive) {
            Snackbar.make(v, R.string.qr_error_no_server, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (currentTargetUrl != null) {
            Intent intent = new Intent(this, PortalActivity.class);
            intent.putExtra("TARGET_URL", currentTargetUrl);
            startActivity(intent);
        }
    }

    private void createFakeSysData(File rootfsDir) {
        try {
            File procDir = new File(rootfsDir, "proc");
            if (!procDir.exists()) procDir.mkdirs();

            // Fake Uptime (Static 2 mins as you requested)
            File uptimeFile = new File(procDir, ".uptime");
            if (!uptimeFile.exists()) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(uptimeFile);
                fos.write("124.08 932.80\n".getBytes());
                fos.close();
            }

            // Fake Version (Fake Kernel IIAB)
            File versionFile = new File(procDir, ".version");
            if (!versionFile.exists()) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(versionFile);
                fos.write("Linux version 6.17.0-PRoot-IIAB (builder@iiab) (Android NDK) #1 SMP PREEMPT Thu Apr 30 20:00:00 UTC 2026\n".getBytes());
                fos.close();
            }

            // Fake Stat (So that Kolibri does not crash reading CPU)
            File statFile = new File(procDir, ".stat");
            if (!statFile.exists()) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(statFile);
                fos.write("cpu  1000 0 1000 10000 0 0 0 0 0 0\n".getBytes());
                fos.close();
            }

            // Fake LoadAvg
            File loadavgFile = new File(procDir, ".loadavg");
            if (!loadavgFile.exists()) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(loadavgFile);
                fos.write("0.00 0.00 0.00 1/1 1\n".getBytes());
                fos.close();
            }

        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to create fake sysdata", e);
        }
    }

    public void handleServerLaunchClick(View v) {
        // Set a hard timeout as a safety net
        timeoutRunnable = () -> {
            if (targetServerState != null) {
                targetServerState = null; // Abort transition
                if (usageFragment != null) runOnUiThread(() -> usageFragment.stopBtnProgress());
                updateUIColorsAndVisibility();
                addToLog(getString(R.string.server_timeout_warning));
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, getResources().getInteger(R.integer.server_cool_off_duration_ms));

        File rootfsDir = new File(getFilesDir(), "rootfs/installed-rootfs/iiab");

        if (!isServerAlive) {
            addToLog("Booting IIAB environment natively...");
            // kernel & uptime
            createFakeSysData(rootfsDir);

            if (serverEngine != null) {
                serverEngine.killProcess();
            }
            serverEngine = new PRootEngine();

            // THE DOCKER TRICK: We start bash as login (-lc), start pdsm, and block the process with tail
            // so that PROoot never closes until we kill it.
            String startCmd = "bash -lc '/usr/local/bin/pdsm start && tail -f /dev/null'";

            serverEngine.executeInContainer(this, rootfsDir.getAbsolutePath(), startCmd, new PRootEngine.OutputListener() {
                @Override
                public void onOutputLine(String line) {
                    runOnUiThread(() -> addToLog("[Server] " + line));
                }

                @Override
                public void onProcessExit(int exitCode) {
                    runOnUiThread(() -> addToLog("[Server] Engine shutdown (Code: " + exitCode + ")"));
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> addToLog("[Server Error] " + error));
                }
            });
            // --- Watchdog injection / foreground service --- //
            prefs.setWatchdogEnable(true);
            enableSystemProtection();
            addToLog(getString(R.string.watchdog_started));
            if (usageFragment != null) usageFragment.startFusionPulse();

            // Fallback for Oppo/Xiaomi: Notify user if server fails to start
            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (targetServerState != null && !isServerAlive) {
                    Snackbar.make(v, R.string.termux_stuck_warning, Snackbar.LENGTH_LONG).show();
                }
            }, getResources().getInteger(R.integer.server_snackbar_delay_ms));

        } else {
            addToLog("Stopping IIAB environment gracefully...");

            PRootEngine stopEngine = new PRootEngine();

            stopEngine.executeInContainer(this, rootfsDir.getAbsolutePath(), "bash -lc '/usr/local/bin/pdsm stop'", new PRootEngine.OutputListener() {
                @Override
                public void onOutputLine(String line) {
                    runOnUiThread(() -> addToLog("[PDSM Stop] " + line));
                }

                @Override
                public void onProcessExit(int exitCode) {
                    runOnUiThread(() -> {
                        // 1. Kill the engine instance
                        if (serverEngine != null) {
                            serverEngine.killProcess();
                            serverEngine = null;
                        }

                        // 2. Zombie Cleanup (Replicating bash pkill script)
                        new Thread(() -> {
                            try {
                                // Force kill any orphaned PRoot children
                                Runtime.getRuntime().exec(new String[]{"sh", "-c", "killall -9 proot 2>/dev/null"});
                            } catch (Exception ignored) {
                            }
                        }).start();

                        // 3. We turned off the Android Watchdog.
                        if (prefs.getWatchdogEnable()) {
                            prefs.setWatchdogEnable(false);
                            disableSystemProtection();
                            addToLog(getString(R.string.watchdog_stopped));
                            if (usageFragment != null) usageFragment.startExitPulse();
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> addToLog("[Stop Error] " + error));
                }
            });
        }
    }

    private void toggleService(boolean stop) {
        prefs.setEnable(!stop);
        savePrefs();
        addToLog(getString(stop ? R.string.vpn_stopping : R.string.vpn_starting));

        if (!stop) {
            runNegotiationSequence();
        } else {
            updateUIColorsAndVisibility();
        }
    }

    public void updateUI() {
        if (usageFragment != null) {
            usageFragment.updateUI();
        }
    }

    private void checkServerStatus() {
        if (isNegotiating) return;

        new Thread(() -> {
            boolean localAlive = pingUrl("http://localhost:8085/home", false);
            boolean vpnOn = prefs.getEnable();
            boolean boxAlive = false;

            if (vpnOn) {
                // The passive radar must also use the proxy to test the tunnel.
                boxAlive = pingUrl("http://box/home", true);
                isProxyDegraded = !boxAlive;
            } else {
                isProxyDegraded = false;
            }

            isServerAlive = localAlive || boxAlive;

            // STATE MACHINE: Has the target state been reached?
            if (targetServerState != null && isServerAlive == targetServerState) {
                targetServerState = null; // Transition complete!
                timeoutHandler.removeCallbacks(timeoutRunnable); // Cancel safety net
                if (usageFragment != null) runOnUiThread(() -> usageFragment.stopBtnProgress());
            }

            if (vpnOn && boxAlive) {
                currentTargetUrl = "http://box/home";
            } else if (localAlive) {
                currentTargetUrl = "http://localhost:8085/home";
            } else {
                currentTargetUrl = null;
            }

            runOnUiThread(this::updateUIColorsAndVisibility);
        }).start();
    }

    public void updateUIColorsAndVisibility() {
        if (usageFragment != null) {
            usageFragment.updateUIColorsAndVisibility();
        }
    }

    public void startTermuxEnvironmentVisible(String actionFlag) {
        android.util.Log.d(TAG, "Legacy Headless command ignored: " + actionFlag);
    }

    // --- TERMUX HEADLESS BRIDGE ---
    public void executeTermuxCommandHeadless(String actionFlag) {
        android.util.Log.d(TAG, "Legacy Headless command ignored: " + actionFlag);
    }

    private void updateConnectivityStatus() {
        boolean isWifiOn = false;
        boolean isHotspotOn = false;
        WifiManager wifiManager = null; // Declare it outside so that it is accessible throughout the function

        try {
            wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            isWifiOn = wifiManager != null && wifiManager.isWifiEnabled();
        } catch (SecurityException e) {
            android.util.Log.w(TAG, "ACCESS_WIFI_STATE permission denied, ignoring Wi-Fi state");
        }

        try {
            // 1. Try standard reflection (Works on older Androids)
            if (wifiManager != null) {
                java.lang.reflect.Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
                method.setAccessible(true);
                isHotspotOn = (Boolean) method.invoke(wifiManager);
            }
        } catch (Throwable e) {
            // 2. Fallback for Android 10+: Check physical network interfaces
            try {
                java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces != null && interfaces.hasMoreElements()) {
                    java.net.NetworkInterface iface = interfaces.nextElement();
                    String name = iface.getName();
                    if ((name.startsWith("ap") || name.startsWith("swlan")) && iface.isUp()) {
                        isHotspotOn = true;
                        break;
                    }
                }
            } catch (Exception ex) {
                // Silently ignore
            }
        }

        // Store states for the QR button logic
        this.isWifiActive = isWifiOn;
        this.isHotspotActive = isHotspotOn;

        if (usageFragment != null) {
            runOnUiThread(() -> usageFragment.updateConnectivityLeds(this.isWifiActive, this.isHotspotActive));
        }
    }

    public void savePrefs() {
        if (usageFragment != null) {
            usageFragment.savePrefsFromUI();
        }
    }

    public void addToLog(String message) {
        if (usageFragment != null && usageFragment.isAdded()) {
            usageFragment.addToLog(message);
        }
    }

    private void setVersionFooter() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;

            String footerText = getString(R.string.version_footer_format, version);

            versionFooter.setText(footerText);
        } catch (PackageManager.NameNotFoundException e) {
            versionFooter.setText(getString(R.string.version_footer_fallback));
        }
    }

    // --- PERMISSION CHECKERS FOR UI OPACITY ---

    private void updateHeaderIconsOpacity() {
        // Verify only the 4 native permissions required by our new monolithic architecture
        boolean hasAllPerms = hasNotifPermission() && hasBatteryPermission() && hasStoragePermission();

        float targetAlpha = hasAllPerms ? 1.0f : 0.4f;

        if (btnSettings != null) btnSettings.setAlpha(targetAlpha);
        if (headerIcon != null) headerIcon.setAlpha(targetAlpha);
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkForUpdates(boolean isManual) {
        if (isManual) {
            runOnUiThread(() -> Toast.makeText(this, R.string.ota_toast_checking, Toast.LENGTH_SHORT).show());
        }

        new Thread(() -> {
            try {
                // Check update JSON data
                Log.d(TAG, "OTA: Connecting to https://iiab.switnet.org/android/apk/update.json");
                URL url = new URL("https://iiab.switnet.org/android/apk/update.json");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "OTA: HTTP response code: " + responseCode);

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    Log.d(TAG, "OTA: Downloaded JSON: " + response.toString());

                    org.json.JSONObject json = new org.json.JSONObject(response.toString());
                    int serverVersionCode = json.getInt("versionCode");
                    String serverVersionName = json.getString("versionName");
                    String apkName = json.getString("apkName");
                    String changelog = json.getString("changelog");

                    // Get current version
                    int currentVersionCode = 0;
                    try {
                        currentVersionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "OTA: Could not get local version code", e);
                    }
                    Log.d(TAG, "OTA: Server Version=" + serverVersionCode + " | Local Version=" + currentVersionCode);

                    // Check against current version
                    if (serverVersionCode > currentVersionCode) {
                        String downloadUrl = "https://iiab.switnet.org/android/apk/" + apkName;
                        runOnUiThread(() -> showUpdateDialog(serverVersionName, changelog, downloadUrl));
                    } else if (isManual) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.ota_toast_latest, Toast.LENGTH_LONG).show());
                    }
                } else if (isManual) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, getString(R.string.ota_toast_error_server, responseCode), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "OTA: Critical error checking for updates", e);
                if (isManual) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.ota_toast_error_network, Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private void showUpdateDialog(String versionName, String changelog, String downloadUrl) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_dialog_title, versionName))
                .setMessage(getString(R.string.update_dialog_message, changelog))
                .setPositiveButton(R.string.update_dialog_positive, (dialog, which) -> {
                    startDownload(downloadUrl);
                })
                .setNegativeButton(R.string.update_dialog_negative, null)
                .setCancelable(false)
                .show();
    }

    private void startDownload(String downloadUrl) {
        // 1. We extract the actual file name from the end of the URL
        String apkName = android.net.Uri.parse(downloadUrl).getLastPathSegment();
        if (apkName == null || !apkName.endsWith(".apk")) {
            apkName = "iiab_update.apk"; // Fallback just in case
        }

        // 2. We save the name in memory so that it is not forgotten if the app closes
        getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .edit().putString("ota_apk_name", apkName).apply();

        // 3. We delete previous failed downloads with THIS same name
        java.io.File oldApk = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                apkName
        );
        if (oldApk.exists()) {
            oldApk.delete();
        }

        android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl));

        request.setTitle(getString(R.string.download_title));
        request.setDescription(getString(R.string.download_description));
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // 4. We tell DownloadManager to use the dynamic name
        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, apkName);

        android.app.DownloadManager manager = (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            updateDownloadId = manager.enqueue(request);
            android.widget.Toast.makeText(this, R.string.download_started_toast, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private final android.content.BroadcastReceiver downloadReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == updateDownloadId) {
                installApk();
            }
        }
    };

    private void installApk() {
        String apkName = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .getString("ota_apk_name", "iiab_update.apk");

        java.io.File apkFile = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                apkName
        );

        if (apkFile.exists()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".provider",
                    apkFile
            );

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            List<android.content.pm.ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "OTA: Error launching installer", e);
                Toast.makeText(this, "Error launching installer. You may need to install the downloaded APK manually from your Downloads folder.", Toast.LENGTH_LONG).show();
            }
        } else {
            Log.e(TAG, "OTA: Downloaded APK file not found at " + apkFile.getAbsolutePath());
        }
    }

    private void setupTerminalSession() {
        com.termux.terminal.TerminalSessionClient client = new com.termux.terminal.TerminalSessionClient() {
            @Override
            public void onTextChanged(com.termux.terminal.TerminalSession session) {
                runOnUiThread(() -> terminalView.invalidate());
            }

            @Override
            public void onTitleChanged(com.termux.terminal.TerminalSession session) {
            }

            // --- THE CLEAN SHUTDOWN (When Bash dies and tells us) ---
            @Override
            public void onSessionFinished(com.termux.terminal.TerminalSession session) {
//                runOnUiThread(() -> {
//                    // 1. Hide the BottomSheet
//                    View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
//                    if (bottomSheet != null) {
//                        com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
//                                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
//                        behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
//                    }
//
//                    // 2. Destroy the instance so a new one spawns next time
//                    terminalSession = null;
//                });
            }

            @Override
            public void onCopyTextToClipboard(com.termux.terminal.TerminalSession session, String text) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("terminal", text);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Copied text", Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onPasteTextFromClipboard(com.termux.terminal.TerminalSession session) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                    if (text != null && terminalSession != null) {
                        terminalSession.write(text.toString());
                    }
                }
            }

            @Override
            public void onBell(com.termux.terminal.TerminalSession session) {
            }

            @Override
            public void onColorsChanged(com.termux.terminal.TerminalSession session) {
            }

            @Override
            public void onTerminalCursorStateChange(boolean state) {
            }

            @Override
            public Integer getTerminalCursorStyle() {
                return 0;
            }

            @Override
            public void setTerminalShellPid(com.termux.terminal.TerminalSession session, int pid) {
            }

            @Override
            public void logError(String tag, String message) {
                Log.e(tag, message);
            }

            @Override
            public void logWarn(String tag, String message) {
                Log.w(tag, message);
            }

            @Override
            public void logInfo(String tag, String message) {
                Log.i(tag, message);
            }

            @Override
            public void logDebug(String tag, String message) {
                Log.d(tag, message);
            }

            @Override
            public void logVerbose(String tag, String message) {
                Log.v(tag, message);
            }

            @Override
            public void logStackTraceWithMessage(String tag, String message, Exception e) {
                Log.e(tag, message, e);
            }

            @Override
            public void logStackTrace(String tag, Exception e) {
                Log.e(tag, "Stack trace", e);
            }
        };

        try {
            // --- DUAL SYSTEM ARCHITECTURE: THE HOST SHELL ---
            // Instead of diving directly into a fragile PRoot guest environment,
            // we drop the expert user into the native Android Host Shell.
            // From here, they can debug, clear directories, or manually trigger PRoot.

            String hostShell = "/system/bin/sh";
            File workingDirectory = getFilesDir(); // Start in the app's secure root

            try {
                File hostBinDir = new File(getFilesDir(), "usr/bin");
                if (!hostBinDir.exists()) hostBinDir.mkdirs();

                File loginScript = new File(hostBinDir, "iiab-login");
                File rootfsDir = new File(getFilesDir(), "rootfs/installed-rootfs/iiab");
                File libproot = new File(getApplicationInfo().nativeLibraryDir, "libproot.so");
                File prootLoader = new File(getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
                File prootLoader32 = new File(getApplicationInfo().nativeLibraryDir, "libproot-loader32.so");
                File tmpDir = new File(getFilesDir(), "proot_tmp");
                if (!tmpDir.exists()) tmpDir.mkdirs();

                // We built the complete PRoot command and saved it in a script
                StringBuilder script = new StringBuilder();
                script.append("#!/system/bin/sh\n");
                script.append("echo 'Entering IIAB Debian Environment...'\n");
                script.append("export PROOT_TMP_DIR=").append(tmpDir.getAbsolutePath()).append("\n");
                script.append("export PROOT_LOADER=").append(prootLoader.getAbsolutePath()).append("\n");
                script.append("export PROOT_LOADER_32=").append(prootLoader32.getAbsolutePath()).append("\n");

                script.append(libproot.getAbsolutePath())
                        // DPKG fix: Added --link2symlink
                        .append(" --sysvipc -0 --link2symlink -k 6.1.0 -r ").append(rootfsDir.getAbsolutePath())
                        .append(" -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard ")
                        .append(" -b ").append(tmpDir.getAbsolutePath()).append(":/tmp ")
                        .append(" -w /root /bin/bash -l -i\n");

                java.io.FileOutputStream fos = new java.io.FileOutputStream(loginScript);
                fos.write(script.toString().getBytes());
                fos.close();

                //
                loginScript.setExecutable(true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create iiab-login script", e);
            }
            // Minimal, bulletproof environment variables
            String[] env = new String[]{
                    "TERM=xterm-256color",
                    "HOME=" + workingDirectory.getAbsolutePath(),
                    // Include the system bins and our W^X fake prefix bins
                    "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:" + workingDirectory.getAbsolutePath() + "/usr/bin"
            };


            // Launch the native Android shell!
            terminalSession = new com.termux.terminal.TerminalSession(
                    hostShell,
                    workingDirectory.getAbsolutePath(),
                    new String[]{"-l"}, // Login shell flag
                    env,
                    2000,
                    client
            );
            terminalView.setTextSize((int) currentTerminalFontSize);

            // --- VIEW CLIENT (Touches, Zoom & Keyboard) ---
            terminalView.setTerminalViewClient(new com.termux.view.TerminalViewClient() {
                @Override
                public float onScale(float scale) {
                    currentTerminalFontSize *= scale;

                    if (currentTerminalFontSize < 10f) currentTerminalFontSize = 10f;
                    if (currentTerminalFontSize > 80f) currentTerminalFontSize = 80f;

                    terminalView.setTextSize((int) currentTerminalFontSize);
                    return 1.0f;
                }

                // --- THE ESCAPE TAP (Crucial for Debugging Limbo Sessions) ---
                @Override
                public void onSingleTapUp(android.view.MotionEvent e) {
                    // If the terminal process is dead (e.g., has crashed or shown "[Process completed]"),
                    // a single tap on the black screen hides the panel and nullifies the session.
                    if (terminalSession != null && !terminalSession.isRunning()) {
                        runOnUiThread(() -> {
                            View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
                            if (bottomSheet != null) {
                                com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
                                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
                            }
                            // Nullify the session to ensure a clean spawn next time.
                            terminalSession = null;
                        });
                        return; // Event consumed.
                    }

                    // --- NORMAL BEHAVIOR FOR ALIVE SESSIONS (Focus and Keyboard) ---
                    terminalView.setFocusable(true);
                    terminalView.setFocusableInTouchMode(true);
                    terminalView.requestFocus();

                    terminalView.post(() -> {
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(terminalView, 0);
                    });
                }

                @Override
                public boolean onLongPress(android.view.MotionEvent e) {
                    return false;
                }

                @Override
                public boolean shouldBackButtonBeMappedToEscape() {
                    return false;
                }

                @Override
                public boolean shouldEnforceCharBasedInput() {
                    return false;
                }

                @Override
                public boolean shouldUseCtrlSpaceWorkaround() {
                    return false;
                }

                @Override
                public boolean isTerminalViewSelected() {
                    return true;
                }

                @Override
                public void copyModeChanged(boolean copyMode) {
                }

                // --- THE ESCAPE HATCH: Listen for hardware ENTER key on dead sessions ---
                @Override
                public boolean onKeyDown(int keyCode, android.view.KeyEvent e, com.termux.terminal.TerminalSession session) {
                    // Return false to let the terminal emulator handle keys internally.
                    // This allows onSessionFinished to trigger if keys are processed.
                    return false;
                }

                @Override
                public boolean onKeyUp(int keyCode, android.view.KeyEvent e) {
                    return false;
                }

                @Override
                public boolean readControlKey() {
                    com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView = findViewById(R.id.extra_keys_view);
                    if (extraKeysView != null) {
                        // Polling the CTRL state natively from the view!
                        Boolean state = extraKeysView.readSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.CTRL, true);
                        return state != null && state;
                    }
                    return false;
                }

                @Override
                public boolean readAltKey() {
                    com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView = findViewById(R.id.extra_keys_view);
                    if (extraKeysView != null) {
                        // Polling the ALT state natively from the view!
                        Boolean state = extraKeysView.readSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.ALT, true);
                        return state != null && state;
                    }
                    return false;
                }

                @Override
                public boolean readShiftKey() {
                    return false;
                }

                @Override
                public boolean readFnKey() {
                    return false;
                }

                @Override
                public boolean onCodePoint(int codePoint, boolean ctrlDown, com.termux.terminal.TerminalSession session) {
                    return false;
                }

                @Override
                public void onEmulatorSet() {
                }

                @Override
                public void logError(String tag, String message) {
                }

                @Override
                public void logWarn(String tag, String message) {
                }

                @Override
                public void logInfo(String tag, String message) {
                }

                @Override
                public void logDebug(String tag, String message) {
                }

                @Override
                public void logVerbose(String tag, String message) {
                }

                @Override
                public void logStackTraceWithMessage(String tag, String message, Exception e) {
                }

                @Override
                public void logStackTrace(String tag, Exception e) {
                }
            });
            // We wait for the view to be drawn before attaching the session to ensure the terminal is ready.
            terminalView.post(() -> {
                if (terminalSession != null) {
                    terminalView.attachSession(terminalSession);

                    // =========================================================
                    // IIAB NATIVE KEYBOARD INTEGRATION
                    // =========================================================
                    try {
                        com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView =
                                findViewById(R.id.extra_keys_view);

                        if (extraKeysView != null) {
                            extraKeysView.loadIIABDefaultKeys();

                            // Listen for normal keys (ESC, TAB, UP, etc)
                            extraKeysView.setExtraKeysViewClient(new com.termux.shared.termux.extrakeys.ExtraKeysView.IExtraKeysView() {
                                @Override
                                public void onExtraKeyButtonClick(View view, com.termux.shared.termux.extrakeys.ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
                                    if (terminalSession != null) {
                                        String key = buttonInfo.getKey();
                                        switch (key) {
                                            case "ESC": terminalSession.write("\033"); break;
                                            case "TAB": terminalSession.write("\t"); break;
                                            case "UP": terminalSession.write("\033[A"); break;
                                            case "DOWN": terminalSession.write("\033[B"); break;
                                            case "RIGHT": terminalSession.write("\033[C"); break;
                                            case "LEFT": terminalSession.write("\033[D"); break;
                                            // --- NEW ORIGINAL TERMUX KEYS ---
                                            case "HOME": terminalSession.write("\033[1~"); break;
                                            case "END": terminalSession.write("\033[4~"); break;
                                            case "PGUP": terminalSession.write("\033[5~"); break;
                                            case "PGDN": terminalSession.write("\033[6~"); break;
                                            default: terminalSession.write(key); break;
                                        }
                                    }
                                }

                                @Override
                                public boolean performExtraKeyButtonHapticFeedback(View view, com.termux.shared.termux.extrakeys.ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
                                    return false; // Let Termux handle the vibration natively
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to initialize Native ExtraKeys", e);
                    }
                }
            }); // <-- Faltaba cerrar el post() y el lambda

        } catch (Exception e) { // <-- Faltaba el catch del try principal de la línea 1232
            Log.e(TAG, "Failed to start Terminal Session", e);
        }
    }

    private void vibrateDevice() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(50);
            }
        }
    }
    // WATCHDOG PROTECTION UTILS
    public void enableSystemProtection() {
        Intent intent = new Intent(this, WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    public void disableSystemProtection() {
        Intent intent = new Intent(this, WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_STOP);
        startService(intent);
    }
}