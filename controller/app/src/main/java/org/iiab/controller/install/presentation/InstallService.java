/*
 * ============================================================================
 * Name        : InstallService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Foreground Service that owns the rootfs install pipeline
 *               (optional wipe -> aria2 download -> tar extract -> chmod ->
 *               companion data: Kiwix + maps Ansible -> finish). Being a
 *               Service, it is independent of the Fragment/Activity lifecycle,
 *               so the install survives a configuration-change recreation
 *               (e.g. the dark/light theme toggle) and app backgrounding.
 *
 *               Progress is published to InstallProgressRepository (the UI
 *               observes it and re-binds after any recreation). Per-line
 *               Ansible/Kiwix output is broadcast (ACTION_INSTALL_LOG) so the
 *               in-app log panel can show it while the screen is open; when the
 *               screen is closed the lines still go to logcat. The service runs
 *               foreground with its own wake/Wi-Fi locks and a progress
 *               notification that offers Cancel. ADFA-4474 (PR2).
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.iiab.controller.Aria2Manager;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.MainActivity;
import org.iiab.controller.PRootEngine;
import org.iiab.controller.R;
import org.iiab.controller.TarExtractor;
import org.iiab.controller.util.ProcessRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;

public final class InstallService extends Service {

    private static final String TAG = "IIAB-InstallService";
    private static final String CHANNEL_ID = "install_channel";
    private static final int NOTIFICATION_ID = 3;

    public static final String ACTION_START = "org.iiab.controller.INSTALL_START";
    public static final String ACTION_CANCEL = "org.iiab.controller.INSTALL_CANCEL";

    // Broadcast of per-line provisioning output (best-effort in-app log).
    public static final String ACTION_INSTALL_LOG = "org.iiab.controller.INSTALL_LOG";
    public static final String EXTRA_LINE = "line";

    // Start extras (snapshotted at start; the pipeline never reads the live UI).
    public static final String EXTRA_TIER = "tier";              // InstallationPlanner.Tier.name()
    public static final String EXTRA_COMPANION = "companion";    // boolean
    public static final String EXTRA_ARCH = "arch";              // termux arch, e.g. arm64-v8a
    public static final String EXTRA_KIWIX_LANG = "kiwixLang";   // nullable override
    public static final String EXTRA_KIWIX_VARIANT = "kiwixVariant"; // nullable override
    public static final String EXTRA_REINSTALL = "reinstall";    // boolean: wipe existing rootfs first

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private Aria2Manager aria2Manager;
    private PRootEngine prootEngine;

    private volatile boolean cancelled = false;
    private volatile boolean finished = false;
    private volatile boolean started = false;

    // Snapshot of the start parameters.
    private InstallationPlanner.Tier tier;
    private boolean companionData;
    private String arch;
    private String overrideKiwixLang;
    private String overrideKiwixVariant;
    private boolean reinstall;

    private File iiabRootDir;     // filesDir/rootfs
    private File debianRootfs;    // filesDir/rootfs/installed-rootfs/iiab

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) {
            doCancel();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }
        if (started) {
            // Ignore a duplicate start (e.g. a double tap); an install is already running.
            return START_NOT_STICKY;
        }
        started = true;

        // Snapshot start parameters.
        String tierName = intent.getStringExtra(EXTRA_TIER);
        tier = parseTier(tierName);
        companionData = intent.getBooleanExtra(EXTRA_COMPANION, false);
        arch = intent.getStringExtra(EXTRA_ARCH);
        if (arch == null) arch = "arm64-v8a";
        overrideKiwixLang = intent.getStringExtra(EXTRA_KIWIX_LANG);
        overrideKiwixVariant = intent.getStringExtra(EXTRA_KIWIX_VARIANT);
        reinstall = intent.getBooleanExtra(EXTRA_REINSTALL, false);

        iiabRootDir = new File(getFilesDir(), "rootfs");
        debianRootfs = new File(iiabRootDir, "installed-rootfs/iiab");

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.install_busy_provisioning)));
        acquireHardwareLocks();
        invalidateModuleStateTrust();

        // Mark "running" immediately so the UI locks and the button shows progress
        // even before aria2 reports the first tick.
        InstallProgressRepository.get().postDownloading(0, "");

        // Run the (blocking) wipe + download kickoff off the main thread.
        new Thread(this::runPipeline, "install-service").start();
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- pipeline

    private void runPipeline() {
        try {
            if (reinstall && debianRootfs.exists() && debianRootfs.isDirectory()) {
                postProvisioning(getString(R.string.install_status_wiping_old));
                try {
                    ProcessRunner.Result wipe = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                    if (!wipe.isSuccess()) {
                        Log.w(TAG, "rm -rf rootfs (reinstall) failed (exit " + wipe.exitCode + "): " + wipe.output);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "rm -rf rootfs (reinstall) failed", e);
                }
            }
            if (cancelled) return;
            startRootfsDownload();
        } catch (Exception e) {
            Log.e(TAG, "Install pipeline crashed", e);
            fail(getString(R.string.install_error_download, String.valueOf(e.getMessage())));
        }
    }

    private void startRootfsDownload() {
        String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "armeabi-v7a" : "arm64-v8a";
        String tierString = tier.name().toLowerCase(Locale.US);
        String directUrl = "https://iiab.switnet.org/android/rootfs/latest_" + tierString + "_" + archSuffix + ".meta4";

        if (aria2Manager == null) aria2Manager = new Aria2Manager();
        aria2Manager.startDownload(this, directUrl, new Aria2Manager.DownloadListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta) {
                if (cancelled) return;
                InstallProgressRepository.get().postDownloading(percentage, speed);
                updateNotification(getString(R.string.install_status_os_download, percentage, speed));
            }

            @Override
            public void onComplete(String downloadPath) {
                if (cancelled) return;
                onRootfsDownloaded(downloadPath);
            }

            @Override
            public void onError(String error) {
                fail(getString(R.string.install_error_download, error));
            }
        });
    }

    private void onRootfsDownloaded(String downloadPath) {
        InstallProgressRepository.get().postExtracting(getString(R.string.install_status_extracting));
        updateNotification(getString(R.string.install_status_extracting));

        File downloadDir = new File(downloadPath);
        File[] archives = downloadDir.listFiles((dir, name) -> name.endsWith(".tar.xz") || name.endsWith(".tar.gz"));
        if (archives == null || archives.length == 0) {
            fail(getString(R.string.install_error_no_archive));
            return;
        }

        File downloadedArchive = archives[0];
        new TarExtractor().startExtraction(this, downloadedArchive.getAbsolutePath(), iiabRootDir.getAbsolutePath(),
                new TarExtractor.ExtractionListener() {
                    @Override
                    public void onComplete(String destDir) {
                        if (cancelled) return;
                        downloadedArchive.delete();
                        File prootTmp = new File(getCacheDir(), "proot_tmp");
                        if (!prootTmp.exists()) prootTmp.mkdirs();
                        File binDir = new File(getFilesDir(), "usr/bin");
                        if (binDir.exists()) {
                            try {
                                ProcessRunner.Result chmod = ProcessRunner.run(new String[]{"chmod", "-R", "755", binDir.getAbsolutePath()});
                                if (!chmod.isSuccess()) {
                                    Log.w(TAG, "chmod on usr/bin failed (exit " + chmod.exitCode + "): " + chmod.output);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "chmod on usr/bin failed", e);
                            }
                        }

                        // DNS is written at the single chokepoint (PRootEngine.executeInContainer),
                        // so the companion-data proot steps below get a working resolv.conf for free.
                        if (companionData) {
                            startCompanionData();
                        } else {
                            finishSuccess();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        fail(getString(R.string.install_error_extraction, error));
                    }
                });
    }

    private void startCompanionData() {
        editLocalVarsForMaps(debianRootfs, tier);
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        String targetLang = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", "en");

        InstallationPlanner.calculateProjectedSize(this, tier, true, targetLang, overrideKiwixVariant,
                new InstallationPlanner.PlanResultListener() {
                    @Override
                    public void onCalculated(InstallationPlanner.StorageProjection projection) {
                        if (cancelled) return;
                        if (projection.resolvedFilename != null) downloadAndIndexKiwix(projection.resolvedFilename);
                        else runMapsAnsible();
                    }

                    @Override
                    public void onError(String error) {
                        runMapsAnsible();
                    }
                });
    }

    private void downloadAndIndexKiwix(String zimFilename) {
        postProvisioning(getString(R.string.install_status_preparing_kiwix));

        String zimUrl = "https://download.kiwix.org/zim/wikipedia/" + zimFilename;
        File libraryDir = new File(debianRootfs, "library/zims/content");
        if (!libraryDir.exists()) libraryDir.mkdirs();

        if (aria2Manager == null) aria2Manager = new Aria2Manager();
        aria2Manager.startDownload(this, zimUrl, new Aria2Manager.DownloadListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta) {
                if (cancelled) return;
                String text = getString(R.string.install_status_zim_download, percentage, speed);
                postProvisioning(text);
                updateNotification(text);
            }

            @Override
            public void onComplete(String downloadPath) {
                if (cancelled) return;
                postProvisioning(getString(R.string.install_status_indexing_zim));
                File downloadedZim = new File(downloadPath, zimFilename);
                if (downloadedZim.exists()) downloadedZim.renameTo(new File(libraryDir, zimFilename));

                if (prootEngine == null) prootEngine = new PRootEngine();
                prootEngine.executeInContainer(InstallService.this, debianRootfs.getAbsolutePath(), "iiab-make-kiwix-lib",
                        new PRootEngine.OutputListener() {
                            @Override public void onOutputLine(String line) { log("[Kiwix] " + line); }
                            @Override public void onProcessExit(int exitCode) { runMapsAnsible(); }
                            @Override public void onError(String error) { runMapsAnsible(); }
                        });
            }

            @Override
            public void onError(String error) {
                runMapsAnsible();
            }
        });
    }

    private void runMapsAnsible() {
        if (cancelled) return;

        if (tier == InstallationPlanner.Tier.BASIC) {
            // BASIC bypass: maps already provisioned in the base image.
            postProvisioning(getString(R.string.install_status_maps_provisioned));
            new Handler(Looper.getMainLooper()).postDelayed(this::finishSuccess, 1500);
            return;
        }

        postProvisioning(getString(R.string.install_status_maps_configuring));
        if (prootEngine == null) prootEngine = new PRootEngine();
        String installCmd = "cd /opt/iiab/iiab && ./runrole --reinstall maps";
        prootEngine.executeInContainer(this, debianRootfs.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override public void onOutputLine(String line) { log("[Ansible] " + line); }
            @Override public void onProcessExit(int exitCode) { finishSuccess(); }
            @Override public void onError(String error) { finishSuccess(); }
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
            text = text.replaceAll("(?m)^maps_install:\\s*.*", "maps_install: True");
            text = text.replaceAll("(?m)^maps_enabled:\\s*.*", "maps_enabled: True");

            if (tier == InstallationPlanner.Tier.STANDARD) {
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: osm-z11");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 7");
            } else if (tier == InstallationPlanner.Tier.FULL) {
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: osm-z11");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 8");
            }
            // BASIC keeps the base-image defaults.

            FileWriter writer = new FileWriter(yamlFile);
            writer.write(text);
            writer.close();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- terminal

    private void finishSuccess() {
        if (finished) return;
        finished = true;
        InstallProgressRepository.get().postSuccess();
        teardown();
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        InstallProgressRepository.get().postFailed(message);
        teardown();
    }

    private void doCancel() {
        if (finished) return;
        cancelled = true;
        finished = true;
        try {
            if (aria2Manager != null) aria2Manager.stopDownload();
        } catch (Exception ignored) {
        }
        InstallProgressRepository.get().postFailed(getString(R.string.install_msg_cancelled));
        teardown();
    }

    private void teardown() {
        releaseHardwareLocks();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // Safety net: if the process is torn down without a clean terminal, do not
        // leave the repository stuck in a running state.
        if (!finished) {
            InstallProgressRepository.get().postIdle();
        }
        releaseHardwareLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private void postProvisioning(String message) {
        InstallProgressRepository.get().postProvisioning(message);
        updateNotification(message);
    }

    private void log(String line) {
        Log.i(TAG, line);
        Intent i = new Intent(ACTION_INSTALL_LOG);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_LINE, line);
        sendBroadcast(i);
    }

    private void invalidateModuleStateTrust() {
        getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("is_module_state_trusted", false).apply();
    }

    private InstallationPlanner.Tier parseTier(String name) {
        if (name != null) {
            try {
                return InstallationPlanner.Tier.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return InstallationPlanner.Tier.BASIC;
    }

    private void acquireHardwareLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IIAB:InstallWakeLock");
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IIAB:InstallWifiLock");
            wifiLock.acquire();
        }
    }

    private void releaseHardwareLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.install_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.install_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Intent cancel = new Intent(this, InstallService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.install_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.install_notif_cancel), cancelIntent)
                .build();
    }

    private void updateNotification(String text) {
        if (finished) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
