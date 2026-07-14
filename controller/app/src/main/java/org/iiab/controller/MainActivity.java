/*
 ============================================================================
 Name        : MainActivity.java
 Contributors: IIAB Project
 Copyright (c) 2026 IIAB Project
 Description : Main Activity
 ============================================================================
 */

package org.iiab.controller;

import org.iiab.controller.util.AppExecutors;

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
import org.iiab.controller.update.presentation.UpdateController;
import androidx.lifecycle.ViewModelProvider;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import org.iiab.controller.util.Snackbars;
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

public class MainActivity extends AppCompatActivity implements TerminalController.Host, ServerController.Host, View.OnClickListener {
    private static final String TAG = "IIAB-MainActivity";
    public Preferences prefs;
    private ImageButton themeToggle;
    private ImageButton btnSettings;
    private android.widget.ImageView headerIcon;

    private UpdateController updateController;

    // Tabs UI
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private TextView versionFooter;
    public boolean isNegotiating = false;
    public Boolean targetServerState = null;
    public String serverTransitionText = "";
    public UsageFragment usageFragment;

    public void setUsageFragment(UsageFragment fragment) {
        this.usageFragment = fragment;
    }


    /** ADFA-4520: last observed native-hotspot state, for the LOHS AND-recommendation. */
    public boolean isHotspotActive() { return serverController.isHotspotActive(); }
    private long pulseStartTime = 0;

    private ActivityResultLauncher<String[]> requestPermissionsLauncher;
    private ActivityResultLauncher<Intent> batteryOptLauncher;

    public boolean isReadingLogs = false;
    private final Handler sizeUpdateHandler = new Handler();
    private Runnable sizeUpdateRunnable;

    public ServerController serverController;

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

    private TerminalController terminalController;

    public void invalidateModuleStateTrust() {
        getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_module_state_trusted", false)
                .apply();
    }

    /**
     * ADFA-4466 Phase 1: single chokepoint for the server-alive polls so we can emit
     * server_started / server_stopped exactly on the transition (consent-gated, no-op
     * otherwise). server_stopped carries a coarse uptime bucket, never an exact duration.
     */

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
    // Listens for commands originating from the 'iiab' bash script in the host terminal
    private final BroadcastReceiver cliReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case "org.iiab.ACTION_BAKE_IMAGE":
                    // Delegated to bash, we do nothing here
                    break;
                case "org.iiab.ACTION_BACKUP_ROOTFS":
                    addToLog(getString(R.string.log_cli_backup_triggered));
                    // triggerBackupProcess();
                    break;
                case "org.iiab.ACTION_RESTORE_ROOTFS":
                    addToLog(getString(R.string.log_cli_restore_triggered));
                    // triggerRestoreProcess();
                    break;
                case "org.iiab.ACTION_PREPARE_ROOTFS":
                    // The terminal requested a clean boot environment
                    File rootfsDir = new File(getFilesDir(), "rootfs/installed-rootfs/iiab");
                    serverController.createFakeSysData(rootfsDir);
                    break;
//                case "org.iiab.ACTION_UNLOCK_SDCARD":
//                    File prootTmp = new File(getFilesDir(), "proot_tmp");
//
//                    runOnUiThread(() -> {
//                        // 1. Ocultar físicamente la ventana de la terminal (BottomSheet)
//                        // Esto mata CUALQUIER intento nativo de Termux de robar el foco o el teclado.
//                        View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
//                        if (bottomSheet != null && bottomSheetBehavior != null) {
//                            bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
//                        }
//
//                        // 2. Por si acaso, quitar cualquier foco residual a nivel Java
//                        if (terminalView != null) {
//                            terminalView.setFocusable(false);
//                            terminalView.setFocusableInTouchMode(false);
//                            terminalView.clearFocus();
//                            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
//                            if (imm != null) imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
//                        }
//
//                        // Opcional: confirmación visual en Java
//                        Toast.makeText(MainActivity.this, "Biometric Requested by Shell", Toast.LENGTH_SHORT).show();
//
//                        // 3. Lanzar la huella (con la terminal ya fuera del camino, Android tendrá la pantalla limpia)
//                        BiometricHelper.prompt(MainActivity.this,
//                                getString(R.string.terminal_auth_title),
//                                getString(R.string.terminal_auth_subtitle),
//                                new BiometricHelper.AuthCallback() {
//                                    @Override
//                                    public void onSuccess() {
//                                        try {
//                                            new File(prootTmp, ".auth_success").createNewFile();
//                                            addToLog(getString(R.string.log_cli_sdcard_granted));
//                                        } catch (Exception ignored) {
//                                        } finally {
//                                            restoreTerminalView();
//                                        }
//                                    }
//
//                                    @Override
//                                    public void onFailed() {
//                                        try {
//                                            new File(prootTmp, ".auth_failed").createNewFile();
//                                            addToLog(getString(R.string.log_cli_sdcard_denied));
//                                        } catch (Exception ignored) {
//                                        } finally {
//                                            restoreTerminalView();
//                                        }
//                                    }
//
//                                    // Método auxiliar para regresar todo a la normalidad
//                                    private void restoreTerminalView() {
//                                        // A. Restaurar el comportamiento de foco
//                                        if (terminalView != null) {
//                                            terminalView.setFocusable(true);
//                                            terminalView.setFocusableInTouchMode(true);
//                                        }
//
//                                        // B. Volver a abrir el BottomSheet al 100% de la pantalla
//                                        if (bottomSheet != null && bottomSheetBehavior != null) {
//                                            // Fuerza la visibilidad por si acaso
//                                            if (bottomSheet.getVisibility() != View.VISIBLE) {
//                                                bottomSheet.setVisibility(View.VISIBLE);
//                                            }
//                                            bottomSheet.bringToFront();
//                                            bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
//
//                                            // C. Devolver el foco a la terminal una vez que ya esté abierta
//                                            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
//                                                if (terminalView != null) terminalView.requestFocus();
//                                            }, 300); // Darle tiempo a la animación de expansión
//                                        }
//                                    }
//                                });
//                    });
//                    break;
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

        if (savedInstanceState == null
                && new org.iiab.controller.feedback.crash.data.CrashReportStore(this).hasPending()) {
            startActivity(new Intent(this, org.iiab.controller.feedback.crash.presentation.CrashReportActivity.class));
        }

        prefs = new Preferences(this);
        setContentView(R.layout.main);

        // --- START TABS & VIEWPAGER ---
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        MainPagerAdapter pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(3);

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

        // ADFA-4538: draggable feedback FAB — present on all tabs (it lives in the activity,
        // above the ViewPager, so it persists across tab switches). Tap -> capture screenshot
        // -> open the feedback form tagged with the current tab.
        com.google.android.material.floatingactionbutton.FloatingActionButton fabFeedback =
                findViewById(R.id.fab_feedback);
        org.iiab.controller.feedback.presentation.FeedbackFab.attach(fabFeedback, () -> {
            int pos = viewPager.getCurrentItem();
            String screen = pos == 0 ? "status" : pos == 1 ? "usage" : pos == 2 ? "deploy"
                    : pos == 3 ? "share" : "main";
            // Direct feedback report (no intermediate form): capture -> build a report with
            // device/rootfs diagnostics + screenshot -> hand to the configured transport, which
            // opens the mail chooser (where the user can add a note or cancel).
            org.iiab.controller.feedback.data.FeedbackScreenshot.capture(MainActivity.this, path -> {
                org.iiab.controller.feedback.domain.FeedbackPayload payload =
                        org.iiab.controller.feedback.domain.FeedbackPayload
                                .builder(org.iiab.controller.feedback.domain.FeedbackType.GENERAL)
                                .appVersion(org.iiab.controller.feedback.data.FeedbackDiagnostics.appVersionName(MainActivity.this))
                                .appBuild(org.iiab.controller.feedback.data.FeedbackDiagnostics.appVersionCode(MainActivity.this))
                                .androidRelease(android.os.Build.VERSION.RELEASE)
                                .device(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                                .abi(new org.iiab.controller.deviceinfo.domain.GetDeviceArchUseCase(
                                        new org.iiab.controller.deviceinfo.data.BuildDeviceAbiProvider()).execute())
                                .binariesTag(org.iiab.controller.feedback.data.FeedbackDiagnostics.binariesTag(MainActivity.this))
                                .screen("main." + screen)
                                .screenshot(path)
                                .build();
                boolean ok = org.iiab.controller.feedback.data.FeedbackConfig.create(MainActivity.this)
                        .send(MainActivity.this, payload);
                if (ok) {
                    org.iiab.controller.analytics.AnalyticsClient.with(MainActivity.this).logFeedbackSent();
                } else {
                    android.widget.Toast.makeText(MainActivity.this, R.string.feedback_no_email_app,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });

        // --- TUTORIAL TAB DETECTOR  ---
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position == 3) { // El índice 3 es la pestaña "Share"
                    showShareTutorialIfNeeded();
                }
            }
        });

        // --- START: EASTER EGG & OTA LOGIC ---
        versionFooter = findViewById(R.id.version_text);
        setVersionFooter();
        updateController = new UpdateController(this);

        terminalController = new TerminalController(this, this);
        terminalController.bind();

        // ADFA-4595: version footer — three gestures:
        //   single tap        -> check version / updates (OTA)
        //   long-press        -> show the main.version help tooltip
        //   double-tap + hold -> open the hidden full terminal (double-tap = key, hold = confirm)
        versionFooter.setOnTouchListener(new View.OnTouchListener() {
            private final Handler handler = new Handler(android.os.Looper.getMainLooper());
            private final int longPressMs = android.view.ViewConfiguration.getLongPressTimeout();
            private final int doubleTapMs = android.view.ViewConfiguration.getDoubleTapTimeout();
            private long downTime = 0L;
            private long lastUpTime = 0L;
            private boolean secondTap = false;
            private boolean longFired = false;
            private Runnable longPress;
            private Runnable singleTap;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downTime = System.currentTimeMillis();
                        secondTap = (downTime - lastUpTime) < doubleTapMs;
                        longFired = false;
                        if (singleTap != null) handler.removeCallbacks(singleTap);
                        final boolean armedForTerminal = secondTap;
                        longPress = () -> {
                            longFired = true;
                            if (armedForTerminal) {
                                terminalController.openFullTerminal();
                            } else {
                                org.iiab.controller.help.TooltipManager.showTooltip(
                                        MainActivity.this, versionFooter,
                                        org.iiab.controller.help.TooltipCategory.K2GO,
                                        org.iiab.controller.help.TooltipTag.MAIN_VERSION);
                            }
                        };
                        handler.postDelayed(longPress, longPressMs);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (longPress != null) handler.removeCallbacks(longPress);
                        long now = System.currentTimeMillis();
                        boolean wasTap = !longFired && (now - downTime) < longPressMs;
                        if (wasTap && !secondTap) {
                            // Might be a single tap; confirm updates only if no second tap follows.
                            lastUpTime = now;
                            singleTap = () -> updateController.checkForUpdatesManual();
                            handler.postDelayed(singleTap, doubleTapMs);
                        } else {
                            lastUpTime = wasTap ? now : 0L;
                        }
                        return true;
                }
                return false;
            }
        });

        viewPager.setCurrentItem(0, false);

        // 1. Initialize Result Launchers
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

        // ADFA-4593: three-tier help — attach tier-1/2 tooltips (long-press) to native controls.
        org.iiab.controller.help.TooltipWiring.wireAll(getWindow().getDecorView());

        // Listeners
        themeToggle.setOnClickListener(v -> toggleTheme());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SetupActivity.class)));

        // --- QR Share Button Logic ---
        btnShareQr.setOnClickListener(v -> {
            if (!ServerStateRepository.get().current().alive) {
                if (viewPager != null) {
                    viewPager.setCurrentItem(1, true);
                }
                if (usageFragment != null) {
                    new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        usageFragment.highlightServerButton();
                    }, 350);
                }

                // Rule 1: Server must be running
                Snackbars.make(findViewById(android.R.id.content), R.string.qr_error_no_server).show();
                return;
            }
            if (!serverController.isWifiActive() && !serverController.isHotspotActive()) {
                // Rule 2: At least one network must be active
                Snackbars.make(findViewById(android.R.id.content), R.string.qr_error_no_network).show();
                return;
            }

            // Launch the new QrActivity
            startActivity(new Intent(MainActivity.this, QrActivity.class));
        });

        applySavedTheme();
        updateUI();

        addToLog(getString(R.string.app_started));
        updateController.checkForUpdates(false);

        sizeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (usageFragment != null && usageFragment.isAdded())
                    usageFragment.updateLogSizeUI();
                sizeUpdateHandler.postDelayed(this, 10000);
            }
        };

        serverController = new ServerController(this, this);
        serverController.start();
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


    private void prepareVpn() {
        BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
    }

    public void startLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
        sizeUpdateHandler.post(sizeUpdateRunnable);
    }

    public void stopLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();

        updateController.unregisterDownloadReceiver();

        stopLogSizeUpdates();
        serverController.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateController.registerDownloadReceiver();
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
        if (usageFragment != null && usageFragment.isLogVisible()) {
            startLogSizeUpdates();
        }
        serverController.onResume();
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

        IntentFilter cliFilter = new IntentFilter();
        cliFilter.addAction("org.iiab.ACTION_BAKE_IMAGE");
        cliFilter.addAction("org.iiab.ACTION_BACKUP_ROOTFS");
        cliFilter.addAction("org.iiab.ACTION_RESTORE_ROOTFS");
        cliFilter.addAction("org.iiab.ACTION_PREPARE_ROOTFS");
//        cliFilter.addAction("org.iiab.ACTION_UNLOCK_SDCARD");

        // cliReceiver MUST be exported to receive commands from the system's 'am' binary
        ContextCompat.registerReceiver(this, cliReceiver, cliFilter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(logReceiver);
        } catch (Exception e) {
        }
        try {
            unregisterReceiver(cliReceiver);
        } catch (Exception e) {
        }
        stopLogSizeUpdates();
    }

    @Override
    protected void onDestroy() {
        // ADFA-4696 (phase 2): release the terminal UI delegate so the session
        // store never holds this destroyed Activity. Running sessions are kept.
        if (terminalController != null) terminalController.detach();
        super.onDestroy();
    }

    @Override
    public void onClick(View view) {
        // Delegated
    }

    public void handleBrowseContentClick(View v) {
        if (!ServerStateRepository.get().current().alive) {
            Snackbars.make(v, R.string.qr_error_no_server).show();
            return;
        }
        String targetUrl = serverController.getCurrentTargetUrl();
        if (targetUrl != null) {
            Intent intent = new Intent(this, PortalActivity.class);
            intent.putExtra("TARGET_URL", targetUrl);
            startActivity(intent);
        }
    }



    public void updateUI() {
        if (usageFragment != null) {
            usageFragment.updateUI();
        }
    }


    public void handleServerLaunchClick(View v) {
        serverController.handleServerLaunchClick(v);
    }

    // --- ServerController.Host ------------------------------------------------
    @Override public void startFusionPulse() { if (usageFragment != null) usageFragment.startFusionPulse(); }
    @Override public void startExitPulse() { if (usageFragment != null) usageFragment.startExitPulse(); }
    @Override public void stopBtnProgress() { if (usageFragment != null) usageFragment.stopBtnProgress(); }
    @Override public void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn) {
        if (usageFragment != null) usageFragment.updateConnectivityLeds(wifiOn, hotspotOn);
    }
    @Override public void refreshServerUi() { updateUIColorsAndVisibility(); }
    @Override public Boolean getTargetServerState() { return targetServerState; }
    @Override public void setTargetServerState(Boolean target) { targetServerState = target; }
    @Override public boolean isNegotiating() { return isNegotiating; }

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


    public void savePrefs() {
        if (usageFragment != null) {
            usageFragment.savePrefsFromUI();
        }
    }

    public void addToLog(String message) {
        // ADFA-4640: single source of truth; the Usage console observes LogRepository.
        LogRepository.get().append(message);
    }

    /**
     * ADFA-4519: show a Snackbar anchored to the Activity CoordinatorLayout, never to a
     * fragment's NestedScrollView root. A ScrollView can host only one direct child, so a
     * Snackbar shown against it during an Activity recreation (e.g. a theme toggle) crashes
     * with "ScrollView can host only one direct child". The CoordinatorLayout is the intended
     * Snackbar host and has no such constraint. No-ops if the Activity is going away.
     */
    public void showSnackbar(CharSequence text) {
        if (isFinishing() || isDestroyed()) return;
        View anchor = findViewById(R.id.main_coordinator);
        if (anchor == null) anchor = findViewById(android.R.id.content);
        if (anchor == null) return;
        Snackbars.make(anchor, text).show();
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


    public void vibrateDevice() {
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

    // TUTORIAL OVERLAY LOGIC
    private void showShareTutorialIfNeeded() {
        SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        boolean hideTutorial = internalPrefs.getBoolean("hide_share_tutorial", false);

        if (hideTutorial) return;

        android.view.ViewGroup root = findViewById(android.R.id.content);
        if (root.findViewById(R.id.tutorial_root) != null) return;

        View tutorialView = getLayoutInflater().inflate(R.layout.overlay_tutorial_share, root, false);
        root.addView(tutorialView);

        // --- Clone QR BUTTON ---
        ImageButton realBtn = findViewById(R.id.btn_share_qr);
        android.widget.ImageView fakeBtn = tutorialView.findViewById(R.id.fake_share_btn);
        View bubbleContainer = tutorialView.findViewById(R.id.bubble_light_container);

        if (realBtn != null && fakeBtn != null) {
            // We copy the icon you are currently using
            fakeBtn.setImageDrawable(realBtn.getDrawable());

            fakeBtn.setOnClickListener(v -> {
                tutorialView.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction(() -> {
                            root.removeView(tutorialView);
                            realBtn.performClick();
                        })
                        .start();
            });

            // We wait for the screen to finish drawing to obtain precise coordinates
            tutorialView.post(() -> {
                // 1. Obtain real coordinates by mitigating the Offset of the Status Bar
                int[] rootLoc = new int[2];
                tutorialView.getLocationInWindow(rootLoc);

                int[] btnLoc = new int[2];
                realBtn.getLocationInWindow(btnLoc);

                float exactX = btnLoc[0] - rootLoc[0];
                float exactY = btnLoc[1] - rootLoc[1];

                // 2. Position the Clone Button
                fakeBtn.setX(exactX);
                fakeBtn.setY(exactY);
                fakeBtn.getLayoutParams().width = realBtn.getWidth();
                fakeBtn.getLayoutParams().height = realBtn.getHeight();
                fakeBtn.requestLayout();

                // 3. Align the needle EXACTLY to the center of the cloned button
                View needle = tutorialView.findViewById(R.id.pointer_needle);
                float needleX = exactX + (realBtn.getWidth() / 2f) - (needle.getWidth() / 2f);
                float needleY = exactY + realBtn.getHeight() - (needle.getHeight() / 2f);
                needle.setX(needleX);
                needle.setY(needleY);

                // 4. Position the blue bubble to align with the needle
                bubbleContainer.setY(needleY + (needle.getHeight() / 2f) - 6);
            });
        }

        // Entry animation
        tutorialView.animate().alpha(1f).setDuration(400).start();

        android.widget.Button btnGotIt = tutorialView.findViewById(R.id.btn_got_it);
        android.widget.CheckBox chkDontShow = tutorialView.findViewById(R.id.chk_dont_show_again);

        btnGotIt.setOnClickListener(v -> {
            if (chkDontShow.isChecked()) {
                internalPrefs.edit().putBoolean("hide_share_tutorial", true).apply();
            }

            tutorialView.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> root.removeView(tutorialView))
                    .start();
        });
    }
}