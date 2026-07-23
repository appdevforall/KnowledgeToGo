/*
 * ============================================================================
 * Name        : ServerController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Native-server lifecycle carved out of MainActivity (ADFA-4578
 *               slice 3, closing the F1 MainActivity decomposition). Owns the
 *               PRoot server start/stop (pdsm), the fake /proc sysdata, the 3s
 *               status + connectivity poll, transition timeout, and the derived
 *               ServerState it publishes to ServerStateRepository. Activity-
 *               scoped; MainActivity forwards onResume/onPause and the control
 *               button. The shared watchdog toggle and the transition-UI state
 *               (used app-wide / by UsageFragment) stay on MainActivity and are
 *               reached through Host. Behaviour-preserving.
 * ============================================================================
 */
package org.iiab.controller;

import org.iiab.controller.config.BoxEndpoints;

import android.content.Context;
import android.os.Handler;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import org.iiab.controller.util.AppExecutors;
import org.iiab.controller.util.Snackbars;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class ServerController {

    private static final String TAG = "IIAB-ServerController";
    private static final int CHECK_INTERVAL_MS = 3000;

    /** Activity-side callbacks the server lifecycle needs. */
    public interface Host {
        void addToLog(String message);
        void startFusionPulse();
        void startExitPulse();
        void stopBtnProgress();
        void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn);
        void refreshServerUi();
        Boolean getTargetServerState();
        void setTargetServerState(Boolean target);
        boolean isNegotiating();
        void enableSystemProtection();
        void disableSystemProtection();
        /** ADFA-4834: the pdsm service currently stopping, for the shutdown screen. */
        default void onShutdownProgress(String service) {}
    }

    private final AppCompatActivity activity;
    private final Host host;
    private final Preferences prefs;

    public PRootEngine serverEngine;
    private long serverUpSinceMs = 0L;
    private boolean isWifiActive = false;
    private boolean isHotspotActive = false;
    private String currentTargetUrl = null;
    // ADFA-4834: hard guard so a repeat "Turn off" tap never spawns a second concurrent pdsm stop.
    private volatile boolean stopping = false;
    private static final java.util.regex.Pattern PDSM_SVC = java.util.regex.Pattern.compile("\\[pdsm:([^\\]]+)\\]");

    private final Handler timeoutHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private final Handler serverCheckHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable serverCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkServerStatus();
            updateConnectivityStatus();
            serverCheckHandler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public ServerController(AppCompatActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.prefs = new Preferences(activity);
    }

    // --- lifecycle (forwarded from MainActivity) --------------------------------

    /** Start the periodic status+connectivity poll (call once from onCreate). */
    public void start() {
        serverCheckHandler.post(serverCheckRunnable);
    }

    public void onResume() {
        updateConnectivityStatus(); // instant refresh when returning to the app
        serverCheckHandler.removeCallbacks(serverCheckRunnable);
        serverCheckHandler.post(serverCheckRunnable);
    }

    public void onPause() {
        serverCheckHandler.removeCallbacks(serverCheckRunnable);
    }

    public String getCurrentTargetUrl() { return currentTargetUrl; }
    public boolean isWifiActive() { return isWifiActive; }
    public boolean isHotspotActive() { return isHotspotActive; }

    // --- server-alive transition analytics -------------------------------------

    private void updateServerAlive(boolean nowAlive) {
        boolean wasAlive = ServerStateRepository.get().current().alive;
        if (nowAlive && !wasAlive) {
            serverUpSinceMs = System.currentTimeMillis();
            org.iiab.controller.analytics.AnalyticsClient.with(activity).logServerStarted();
        } else if (!nowAlive && wasAlive) {
            long uptime = serverUpSinceMs > 0L ? System.currentTimeMillis() - serverUpSinceMs : -1L;
            serverUpSinceMs = 0L;
            org.iiab.controller.analytics.AnalyticsClient.with(activity).logServerStopped(uptime);
        }
        // The repository is updated by the poll (checkServerStatus) right after this.
    }

    private boolean pingUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            return (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400);
        } catch (Exception e) {
            return false;
        }
    }

    // --- status poll ------------------------------------------------------------

    private void checkServerStatus() {
        if (host.isNegotiating()) return;

        AppExecutors.get().io().execute(() -> {
            boolean localAlive = pingUrl(BoxEndpoints.BASE + "/home");

            updateServerAlive(localAlive);

            // ADFA-4578: evaluate + publish the SystemState at app level (every poll),
            // so every tab reflects the server live instead of only after visiting the Dashboard.
            final DashboardFragment.SystemState sysState = SystemStateEvaluator.evaluate(activity, localAlive);
            ServerStateRepository.get().post(ServerState.of(localAlive, sysState));

            // STATE MACHINE: Has the target state been reached?
            Boolean target = host.getTargetServerState();
            if (target != null && ServerStateRepository.get().current().alive == target) {
                host.setTargetServerState(null); // Transition complete!
                timeoutHandler.removeCallbacks(timeoutRunnable); // Cancel safety net
                activity.runOnUiThread(host::stopBtnProgress);
            }

            currentTargetUrl = localAlive ? BoxEndpoints.BASE + "/home" : null;

            activity.runOnUiThread(host::refreshServerUi);
        });
    }

    private void updateConnectivityStatus() {
        boolean isWifiOn = false;
        boolean isHotspotOn = false;
        android.net.wifi.WifiManager wifiManager = null;

        try {
            wifiManager = (android.net.wifi.WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            isWifiOn = wifiManager != null && wifiManager.isWifiEnabled();
        } catch (SecurityException e) {
            android.util.Log.w(TAG, "ACCESS_WIFI_STATE permission denied, ignoring Wi-Fi state");
        }

        try {
            if (wifiManager != null) {
                java.lang.reflect.Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
                method.setAccessible(true);
                isHotspotOn = (Boolean) method.invoke(wifiManager);
            }
        } catch (Throwable e) {
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

        this.isWifiActive = isWifiOn;
        this.isHotspotActive = isHotspotOn;

        final boolean wifi = isWifiOn, hotspot = isHotspotOn;
        activity.runOnUiThread(() -> host.updateConnectivityLeds(wifi, hotspot));
    }

    // --- fake /proc sysdata for the container -----------------------------------

    public void createFakeSysData(File rootfsDir) {
        try {
            File procDir = new File(rootfsDir, "proc");
            if (!procDir.exists()) procDir.mkdirs();

            long uptimeMillis = android.os.SystemClock.elapsedRealtime();
            long bootTimeSeconds = (System.currentTimeMillis() - uptimeMillis) / 1000;
            double uptimeSeconds = uptimeMillis / 1000.0;

            File uptimeFile = new File(procDir, ".uptime");
            if (uptimeFile.exists()) uptimeFile.delete();
            java.io.FileOutputStream fosUp = new java.io.FileOutputStream(uptimeFile);
            fosUp.write(String.format(java.util.Locale.US, "%.2f %.2f\n", uptimeSeconds, uptimeSeconds).getBytes());
            fosUp.close();

            File versionFile = new File(procDir, ".version");
            if (!versionFile.exists()) {
                java.io.FileOutputStream fosVer = new java.io.FileOutputStream(versionFile);
                fosVer.write("Linux version 6.17.0-PRoot-IIAB (builder@iiab) (Android NDK) #1 SMP PREEMPT Thu Apr 30 20:00:00 UTC 2026\n".getBytes());
                fosVer.close();
            }

            File statFile = new File(procDir, ".stat");
            if (statFile.exists()) statFile.delete();
            java.io.FileOutputStream fosStat = new java.io.FileOutputStream(statFile);
            String statContent = "cpu  1000 0 1000 10000 0 0 0 0 0 0\n" +
                    "btime " + bootTimeSeconds + "\n";
            fosStat.write(statContent.getBytes());
            fosStat.close();

            File loadavgFile = new File(procDir, ".loadavg");
            if (!loadavgFile.exists()) {
                java.io.FileOutputStream fosLoad = new java.io.FileOutputStream(loadavgFile);
                fosLoad.write("0.00 0.00 0.00 1/1 1\n".getBytes());
                fosLoad.close();
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to create dynamic fake sysdata", e);
        }
    }

    // --- server start / stop (the control button) -------------------------------

    public void handleServerLaunchClick(View v) {
        // ADFA-4621 safety net: never start/stop the server during a rootfs/module install.
        if (org.iiab.controller.install.presentation.InstallProgressRepository.get().isRunning()
                || org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()
                || InstallGuard.inProgress(activity)) {   // ADFA-4811: durable guard survives a mid-install kill
            host.setTargetServerState(null);
            activity.runOnUiThread(host::stopBtnProgress);
            host.refreshServerUi();
            return;
        }
        // Set a hard timeout as a safety net
        timeoutRunnable = () -> {
            if (host.getTargetServerState() != null) {
                host.setTargetServerState(null); // Abort transition
                activity.runOnUiThread(host::stopBtnProgress);
                host.refreshServerUi();
                host.addToLog(activity.getString(R.string.server_timeout_warning));
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, activity.getResources().getInteger(R.integer.server_cool_off_duration_ms));

        File rootfsDir = new File(activity.getFilesDir(), "rootfs/installed-rootfs/iiab");

        if (!ServerStateRepository.get().current().alive) {
            host.addToLog(activity.getString(R.string.log_server_booting_native));
            createFakeSysData(rootfsDir);

            if (serverEngine != null) {
                serverEngine.killProcess();
            }
            serverEngine = new PRootEngine();

            String startCmd = "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm start && tail -f /dev/null'";

            serverEngine.executeInContainer(activity, rootfsDir.getAbsolutePath(), startCmd, new PRootEngine.OutputListener() {
                @Override
                public void onOutputLine(String line) {
                    activity.runOnUiThread(() -> host.addToLog("[Server] " + line));
                }

                @Override
                public void onProcessExit(int exitCode) {
                    activity.runOnUiThread(() -> host.addToLog(activity.getString(R.string.log_server_engine_shutdown, exitCode)));
                }

                @Override
                public void onError(String error) {
                    activity.runOnUiThread(() -> host.addToLog(activity.getString(R.string.log_server_error, error)));
                }
            });
            // --- Watchdog injection / foreground service --- //
            prefs.setWatchdogEnable(true);
            host.enableSystemProtection();
            host.addToLog(activity.getString(R.string.watchdog_started));
            host.startFusionPulse();

            // Fallback for Oppo/Xiaomi: Notify user if server fails to start
            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (host.getTargetServerState() != null && !ServerStateRepository.get().current().alive) {
                    Snackbars.make(v, R.string.termux_stuck_warning).show();
                }
            }, activity.getResources().getInteger(R.integer.server_snackbar_delay_ms));

        } else {
            if (stopping) return;   // ADFA-4834: a stop is already in flight; ignore repeat taps
            stopping = true;
            host.addToLog(activity.getString(R.string.log_server_stopping_gracefully));

            PRootEngine stopEngine = new PRootEngine();

            stopEngine.executeInContainer(activity, rootfsDir.getAbsolutePath(), "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm stop'", new PRootEngine.OutputListener() {
                @Override
                public void onOutputLine(String line) {
                    activity.runOnUiThread(() -> host.addToLog("[PDSM Stop] " + line));
                    // ADFA-4834: surface which service is stopping to the shutdown screen.
                    java.util.regex.Matcher m = PDSM_SVC.matcher(line);
                    if (m.find()) {
                        final String svc = m.group(1);
                        activity.runOnUiThread(() -> host.onShutdownProgress(svc));
                    }
                }

                @Override
                public void onProcessExit(int exitCode) {
                    activity.runOnUiThread(() -> {
                        stopping = false;   // ADFA-4834: stop finished; allow a future stop
                        if (serverEngine != null) {
                            serverEngine.killProcess();
                            serverEngine = null;
                        }

                        AppExecutors.get().io().execute(() -> {
                            // ADFA-4811: never global-kill proot while an install is running — it
                            // would kill the in-flight installer's proot over the shared rootfs.
                            if (InstallGuard.inProgress(activity)) {
                                return;
                            }
                            try {
                                Runtime.getRuntime().exec(new String[]{"sh", "-c", "killall -9 proot 2>/dev/null"});
                            } catch (Exception ignored) {
                            }
                        });

                        if (prefs.getWatchdogEnable()) {
                            prefs.setWatchdogEnable(false);
                            host.disableSystemProtection();
                            host.addToLog(activity.getString(R.string.watchdog_stopped));
                            host.startExitPulse();
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    stopping = false;   // ADFA-4834: allow a retry if the stop failed to launch
                    activity.runOnUiThread(() -> host.addToLog(activity.getString(R.string.log_server_stop_error, error)));
                }
            });
        }
    }
}
