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
import org.iiab.controller.ModuleRegistry;
import org.iiab.controller.PRootEngine;
import org.iiab.controller.R;
import org.iiab.controller.TarExtractor;
import org.iiab.controller.util.ProcessRunner;
import org.iiab.controller.deploy.domain.ModuleName;
import org.iiab.controller.install.domain.AnsibleRunOutcome;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.Locale;

public final class InstallService extends Service {

    private static final String TAG = "IIAB-InstallService";
    private static final String CHANNEL_ID = "install_channel";
    private static final int NOTIFICATION_ID = 3;

    public static final String ACTION_START = "org.iiab.controller.INSTALL_START";
    public static final String ACTION_CANCEL = "org.iiab.controller.INSTALL_CANCEL";
    // Per-module install queue (ADFA-4476 slice 3): distinct from the rootfs ACTION_START.
    public static final String ACTION_START_MODULES = "org.iiab.controller.INSTALL_START_MODULES";

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
    public static final String EXTRA_MODULES = "modules";        // String[]: module yamlBaseKeys to install

    // Which pipeline to run (ADFA-4476). Absent/"install" -> normal install; "reset" -> scratch reset.
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SKIP_MAPS = "skipMaps"; // content flow: maps ship in the base image
    public static final String MODE_INSTALL = "install";
    public static final String MODE_RESET = "reset";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private Aria2Manager aria2Manager;
    private PRootEngine prootEngine;
    private org.iiab.controller.content.RestContentClient restContentClient;   // ADFA-4840 (was socket.io, ADFA-4832)

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
    private boolean skipMaps;
    private boolean resetMode;

    // ADFA-4476 slice 3: per-module install queue state (module mode only).
    private boolean moduleMode;
    private java.util.Deque<String> moduleQueue;
    private java.util.List<String> failedModules;

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
        boolean isModules = ACTION_START_MODULES.equals(action);
        if (!ACTION_START.equals(action) && !isModules) {
            return START_NOT_STICKY;
        }
        if (started) {
            // Ignore a duplicate start (e.g. a double tap); an operation is already running.
            return START_NOT_STICKY;
        }
        started = true;
        moduleMode = isModules;
        // ADFA-4811: durable marker so the app stands back (no auto-start, keep the boot gate,
        // isSystemInstalled=false, no global proot kill) until this install reaches a clean terminal.
        org.iiab.controller.InstallGuard.begin(this);

        iiabRootDir = new File(getFilesDir(), "rootfs");
        debianRootfs = new File(iiabRootDir, "installed-rootfs/iiab");

        if (isModules) {
            // ADFA-4476 slice 3: the service owns the module install queue, so it survives a
            // recreation (theme toggle / rotation) and can never launch two concurrent runroles.
            String[] mods = intent.getStringArrayExtra(EXTRA_MODULES);
            moduleQueue = new java.util.ArrayDeque<>();
            if (mods != null) {
                for (String m : mods) if (m != null && !m.isEmpty()) moduleQueue.add(m);
            }
            failedModules = new java.util.ArrayList<>();

            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.install_busy_modules)));
            acquireHardwareLocks();
            persistQueue();
            // Mark "running" immediately (currentModule null until the first dequeue) so the UI
            // locks and a resume cannot start a second loop.
            ModuleQueueRepository.get().postRunning(null, moduleQueue.size());
            new Thread(this::runModuleQueue, "module-queue-service").start();
            return START_NOT_STICKY;
        }

        // Snapshot start parameters (rootfs install).
        String tierName = intent.getStringExtra(EXTRA_TIER);
        tier = parseTier(tierName);
        companionData = intent.getBooleanExtra(EXTRA_COMPANION, false);
        arch = intent.getStringExtra(EXTRA_ARCH);
        if (arch == null) arch = "arm64-v8a";
        overrideKiwixLang = intent.getStringExtra(EXTRA_KIWIX_LANG);
        overrideKiwixVariant = intent.getStringExtra(EXTRA_KIWIX_VARIANT);
        reinstall = intent.getBooleanExtra(EXTRA_REINSTALL, false);
        skipMaps = intent.getBooleanExtra(EXTRA_SKIP_MAPS, false);
        resetMode = MODE_RESET.equals(intent.getStringExtra(EXTRA_MODE));

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.install_busy_provisioning)));
        acquireHardwareLocks();
        invalidateModuleStateTrust();

        if (resetMode) {
            // Scratch reset (ADFA-4476): wipe -> download Debian base -> extract -> bootstrap.
            InstallProgressRepository.get().beginReset();
            // Mark "running" immediately so the UI locks before the wipe starts.
            InstallProgressRepository.get().postProvisioning(getString(R.string.install_status_wiping_old));
            new Thread(this::runResetPipeline, "reset-service").start();
        } else {
            InstallProgressRepository.get().beginInstall();
            // Mark "running" immediately so the UI locks and the button shows progress
            // even before aria2 reports the first tick.
            InstallProgressRepository.get().postDownloading(0, "");
            // Run the (blocking) wipe + download kickoff off the main thread.
            new Thread(this::runPipeline, "install-service").start();
        }
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- pipeline

    /** Record the tier being installed so a later content-only "Get more" can size correctly. */
    private void persistInstalledTier() {
        try {
            getSharedPreferences(getString(R.string.pref_file_internal), android.content.Context.MODE_PRIVATE)
                    .edit().putString("installed_tier", tier.name()).apply();
        } catch (Exception ignore) { /* best-effort */ }
    }

    private void runPipeline() {
        try {
            // Non-destructive guard (ADFA-4725): an already-installed system is NEVER
            // re-extracted unless an explicit reinstall was requested. "Get more" then only
            // runs the additive companion-data steps (Kiwix zims + Maps) inside the existing
            // rootfs, so the OS blocks and any customized content are left untouched.
            if (!reinstall && debianRootfs.exists() && debianRootfs.isDirectory()) {
                if (companionData) startCompanionData();
                else finishSuccess();
                return;
            }
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
            persistInstalledTier();
            startRootfsDownload();
        } catch (Exception e) {
            Log.e(TAG, "Install pipeline crashed", e);
            org.iiab.controller.analytics.AnalyticsClient.with(this).logInstallFailed("download", "exception");
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
                org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this).logInstallFailed("download", "network");
                fail(getString(R.string.install_error_download, error));
            }

            @Override
            public void onIntegrityFailure(String reason) {
                // ADFA-4676: the download completed but failed the app-side integrity
                // gate (size/SHA-256). Surface it as a verification failure, not a
                // generic network error.
                Log.e(TAG, "Rootfs download failed integrity verification: " + reason);
                org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this).logInstallFailed("download", "verify");
                fail(getString(R.string.install_error_verify));
            }
        });
    }

    private void onRootfsDownloaded(String downloadPath) {
        InstallProgressRepository.get().postExtracting(getString(R.string.install_status_extracting));
        updateNotification(getString(R.string.install_status_extracting));

        File downloadDir = new File(downloadPath);
        File[] archives = downloadDir.listFiles((dir, name) -> name.endsWith(".tar.xz") || name.endsWith(".tar.gz"));
        if (archives == null || archives.length == 0) {
            org.iiab.controller.analytics.AnalyticsClient.with(this).logInstallFailed("download", "no_archive");
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
                        org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this).logInstallFailed("extract", "extract_error");
                        fail(getString(R.string.install_error_extraction, error));
                    }
                });
    }

    private void startCompanionData() {
        editLocalVarsForMaps(debianRootfs, tier);
        SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        String targetLang = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", org.iiab.controller.applang.data.ContentLanguage.systemDefault());

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
        // ADFA-4832: on an already-running system, adding a ZIM via a second proot
        // (iiab-make-kiwix-lib) collides with the live server and breaks Kiwix. Route the add
        // through the in-server dashboard channel — the running server downloads + indexes
        // in-process, no new proot. The app-side proot path below stays for the fresh-install
        // case (server down), which safely owns the rootfs exclusively.
        if (org.iiab.controller.ServerStateRepository.get().current().alive) {
            addZimViaLiveChannel(zimFilename);
            return;
        }

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

    /**
     * ADFA-4840: add a ZIM on the LIVE system through the in-server durable REST job engine, so the
     * running server does the download + index in-process (no second proot). Short POST + ~1s polls
     * instead of a long-lived socket; the job is durable server-side, so it survives UI/config churn
     * and even a dashboard restart. This service is foreground, so polling continues in the background.
     * On success we finish here rather than running the maps proot — maps on a live system needs the
     * same migration (follow-up), and spawning that proot would re-introduce the collision.
     */
    private void addZimViaLiveChannel(String zimFilename) {
        postProvisioning(getString(R.string.install_status_preparing_kiwix));
        restContentClient = new org.iiab.controller.content.RestContentClient();
        restContentClient.addZim(zimFilename, new org.iiab.controller.content.RestContentClient.Listener() {
            @Override public void onProgress(int percent, String speed) {
                if (cancelled) return;
                // ADFA-4830: install_status_zim_download no longer bakes the unit — the rate carries
                // its own localized "/s". The live channel gives a raw rate, so append it here.
                String rate = speed + getString(R.string.k2go_rate_per_second);
                String text = getString(R.string.install_status_zim_download, percent, rate);
                postProvisioning(text);
                updateNotification(text);
            }
            @Override public void onIndexing() {
                if (cancelled) return;
                postProvisioning(getString(R.string.install_status_indexing_zim));
            }
            @Override public void onLog(String line) { log("[Kiwix-live] " + line); }
            @Override public void onDone() {
                if (cancelled) return;
                finishSuccess();
            }
            @Override public void onError(String message) {
                if (cancelled) return;
                log("[Kiwix-live] add failed: " + message);
                // Safe degrade: never spawn a colliding proot on the live system — surface the failure.
                fail(getString(R.string.install_error_download, message));
            }
        });
    }

    private void runMapsAnsible() {
        if (cancelled) return;

        if (skipMaps || tier == InstallationPlanner.Tier.BASIC) {
            // Maps ship in the software block (base image) and BASIC already has them; the
            // content flow disables the maps reinstall. Skip straight to success.
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

    // ------------------------------------------------------------ reset pipeline

    /**
     * Scratch reset (ADFA-4476): wipe the installed rootfs, download the Debian
     * base tarball, extract it and bootstrap IIAB. Same steps as the former
     * inline flow in ResetDeleteController, now owned by the service so it
     * survives a configuration-change recreation. Progress is tagged RESET.
     */
    private void runResetPipeline() {
        try {
            // 1. WIPE
            postProvisioning(getString(R.string.install_status_wiping_old));
            try {
                ProcessRunner.Result wipe = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                if (!wipe.isSuccess()) {
                    Log.w(TAG, "rm -rf rootfs (reset) failed (exit " + wipe.exitCode + "): " + wipe.output);
                }
            } catch (Exception e) {
                Log.w(TAG, "rm -rf rootfs (reset) failed", e);
            }
            debianRootfs.mkdirs();
            if (cancelled) return;

            // 2. DOWNLOAD
            InstallProgressRepository.get().postDownloading(0, "");
            updateNotification(getString(R.string.install_status_downloading_debian));

            String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "arm" : "aarch64";
            final String tarball = "debian-trixie-" + archSuffix + "-pd-v4.29.0.tar.xz";
            String url = "https://iiab.switnet.org/android/rootfs/proot-distro-v4.29.0/" + tarball;

            if (aria2Manager == null) aria2Manager = new Aria2Manager();
            aria2Manager.startDownload(this, url, new Aria2Manager.DownloadListener() {
                @Override
                public void onProgress(int percentage, String speed, String eta) {
                    if (cancelled) return;
                    InstallProgressRepository.get().postDownloading(percentage, speed);
                    updateNotification(getString(R.string.install_status_debian_download, percentage, speed));
                }

                @Override
                public void onComplete(String downloadPath) {
                    if (cancelled) return;
                    resetExtractAndBootstrap(downloadPath, tarball);
                }

                @Override
                public void onError(String error) {
                    fail(getString(R.string.install_error_download, error));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Reset pipeline crashed", e);
            fail(getString(R.string.install_error_reset, String.valueOf(e.getMessage())));
        }
    }

    /** Extract the downloaded Debian tarball (xz | tar) and bootstrap IIAB. */
    private void resetExtractAndBootstrap(String downloadPath, String tarball) {
        try {
            // 3. EXTRACT
            InstallProgressRepository.get().postExtracting(getString(R.string.install_status_extracting_base));
            updateNotification(getString(R.string.install_status_extracting_base));

            File downloadedArchive = new File(downloadPath, tarball);
            File staticTar = new File(getApplicationInfo().nativeLibraryDir, "libtar.so");
            File staticXz = new File(getApplicationInfo().nativeLibraryDir, "libxz.so");
            String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";
            String xzBin = staticXz.exists() ? staticXz.getAbsolutePath() : "xz";

            // Pipe xz directly into tar to bypass Android's limited PATH.
            // D2 follow-up (ADFA-4718): single-quote the interpolated binary/paths so the pipe
            // is robust even if a path ever contained spaces/metacharacters (app-internal today),
            // matching the backup pipe (D11). The literal --exclude keeps its own quoting.
            String extractCmd = "'" + xzBin + "' -d -c '" + downloadedArchive.getAbsolutePath() + "' | '" + tarBin
                    + "' --exclude='*/dev/*' --strip-components=1 -xf - -C '" + debianRootfs.getAbsolutePath() + "'";

            Process pExt = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", extractCmd});
            BufferedReader errReader = new BufferedReader(new InputStreamReader(pExt.getErrorStream()));
            StringBuilder errMsg = new StringBuilder();
            String errLine;
            while ((errLine = errReader.readLine()) != null) {
                errMsg.append(errLine).append("\n");
                Log.e(TAG, "[TAR Extractor] " + errLine);
            }
            int exitCode = pExt.waitFor();
            if (exitCode != 0) {
                throw new Exception("Extraction failed (Code " + exitCode + "):\n" + errMsg.toString());
            }
            downloadedArchive.delete();
            if (cancelled) return;

            // 4. BOOTSTRAP IIAB
            postProvisioning(getString(R.string.install_status_bootstrapping));

            // DNS is written at the chokepoint (PRootEngine) before the bootstrap run.
            if (prootEngine == null) prootEngine = new PRootEngine();
            String bootstrapCmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && "
                    + "export DEBIAN_FRONTEND=noninteractive && "
                    + "apt-get update && apt-get install -y curl ca-certificates nano sudo && "
                    + "curl -fsSL https://raw.githubusercontent.com/appdevforall/KnowledgeToGo/main/iiab-android -o /usr/local/sbin/iiab-android && "
                    + "chmod +x /usr/local/sbin/iiab-android && "
                    + "apt-get clean && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* /tmp/* /root/.cache";

            prootEngine.executeInContainer(this, debianRootfs.getAbsolutePath(), "/bin/bash -c '" + bootstrapCmd + "'",
                    new PRootEngine.OutputListener() {
                        @Override public void onOutputLine(String line) { log("[Bootstrap] " + line); }
                        @Override public void onProcessExit(int exitCode2) { finishSuccess(); }
                        @Override public void onError(String error) { fail(getString(R.string.install_error_bootstrap, error)); }
                    });
        } catch (Exception e) {
            fail(getString(R.string.install_error_extract_bootstrap, e.getMessage()));
        }
    }

    // ------------------------------------------------------------ module queue

    private void runModuleQueue() {
        // ADFA-4842: a runrole modifies system packages/config, so it must own the rootfs
        // exclusively. Unlike content (which the running server handles in-process), we cannot
        // avoid the runrole's own proot — so instead stop the server's SERVICES first (pdsm stop)
        // so a runrole never runs alongside a live server writing the same DBs/config (the
        // data-corruption risk). The app restarts the server after the queue (LibraryActivity's
        // module-queue observer). Stopping is idempotent (no-op if the server is already down).
        if (cancelled) return;
        updateNotification(getString(R.string.server_shutting_down));
        log("[Modules] Stopping server services before runroles (exclusive rootfs)...");
        if (prootEngine == null) prootEngine = new PRootEngine();
        prootEngine.executeInContainer(this, debianRootfs.getAbsolutePath(),
                "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm stop'",
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { log("[Modules] pdsm stop: " + line); }
                    @Override public void onProcessExit(int exitCode) { installNextModule(); }
                    @Override public void onError(String error) {
                        // Proceed anyway: if the stop couldn't launch, the runroles still need to run;
                        // surface it in the log rather than hanging the queue.
                        log("[Modules] pdsm stop error (continuing): " + error);
                        installNextModule();
                    }
                });
    }

    /**
     * Dequeue and install one module, then chain to the next from the proot callback.
     * Owned by the service, so a Fragment recreation cannot start a second loop and there
     * is never more than one runrole in flight (ADFA-4476 slice 3, superseding the
     * ADFA-4458/4519 Fragment-scoped re-entry guard).
     */
    private void installNextModule() {
        if (cancelled) return;
        if (moduleQueue.isEmpty()) {
            finishModuleQueue();
            return;
        }
        final String nextModule = moduleQueue.poll();
        persistQueue();

        // D2: nextModule is interpolated into a command run as root inside the container
        // (sed/echo/runrole). Only allow names from the known catalog with no shell
        // metacharacters; fail closed and skip anything else.
        if (!ModuleName.isAllowed(nextModule, ModuleRegistry.validYamlKeys())) {
            Log.e(TAG, "Refusing to install unrecognized/unsafe module name: " + nextModule);
            log("[Security] Skipped invalid module: " + nextModule);
            installNextModule();
            return;
        }

        ModuleQueueRepository.get().postRunning(nextModule, moduleQueue.size());
        updateNotification(getString(R.string.install_status_installing_module, nextModule));

        if (prootEngine == null) prootEngine = new PRootEngine();

        // Speculative local_vars edit BEFORE runrole (same command as the former Fragment loop):
        // reverted on failure so a failed module is not left looking installed/enabled.
        // ADFA-4629: the Ansible ROLE directory name can differ from the local_vars
        // variable base -- upstream's runrole documents this (role 'calibre-web' uses var
        // 'calibreweb'; also iiab-admin, osm-vector-maps). Keep writing <varBase>_install/
        // enabled, but hand runrole the ROLE name. RoleNames is a hardcoded, unit-tested
        // map, so the value is safe to interpolate; assert well-formed as a defensive D2 guard.
        final String roleName = org.iiab.controller.deploy.domain.RoleNames.roleFor(nextModule);
        if (!org.iiab.controller.deploy.domain.ModuleName.isWellFormed(roleName)) {
            Log.e(TAG, "Refusing runrole with a malformed role name: " + roleName);
            log("[Security] Skipped invalid role name: " + roleName);
            installNextModule();
            return;
        }

        String installCmd = "sed -i -E '/^[[:space:]]*" + nextModule + "_(install|enabled)[[:space:]]*:/d' /etc/iiab/local_vars.yml && " +
                "echo '" + nextModule + "_install: True' >> /etc/iiab/local_vars.yml && " +
                "echo '" + nextModule + "_enabled: True' >> /etc/iiab/local_vars.yml && " +
                "cd /opt/iiab/iiab && ./runrole " + roleName;

        // ADFA-4435: Ansible can print its failure to stdout yet still exit 0, so the verdict
        // considers the output as well as the exit code (pure, unit-tested domain object).
        final AnsibleRunOutcome outcome = new AnsibleRunOutcome();
        prootEngine.executeInContainer(this, debianRootfs.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                outcome.observe(line);
                log("[Ansible] " + line);
            }

            @Override
            public void onProcessExit(int exitCode) {
                if (cancelled) return;
                // Phantom-process killer (Android 12+) can SIGKILL container children -> exit 137.
                if (exitCode == 137) log("[Install] " + nextModule + " killed by the system (exit 137)");
                if (outcome.failed(exitCode)) {
                    failedModules.add(nextModule);
                    log("[Install] FAILED: " + nextModule + " (exit=" + exitCode + ")");
                    org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this).logModuleInstall(nextModule, false);
                    revertModuleInLocalVars(nextModule, InstallService.this::installNextModule);
                } else {
                    org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this).logModuleInstall(nextModule, true);
                    installNextModule();
                }
            }

            @Override
            public void onError(String error) {
                if (cancelled) return;
                // The container could not run at all: report this module and stop the batch
                // (matches the former loop, which aborted on a proot error).
                failedModules.add(nextModule);
                log("[Install] ERROR: " + nextModule + " (" + error + ")");
                org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this).logModuleInstall(nextModule, false);
                moduleQueue.clear();
                revertModuleInLocalVars(nextModule, InstallService.this::finishModuleQueue);
            }
        });
    }

    /**
     * ADFA-4435: roll back the speculative local_vars edit made before runrole, so a failed
     * install is not left looking installed/enabled. Always runs {@code then} afterwards.
     */
    private void revertModuleInLocalVars(String module, Runnable then) {
        if (prootEngine == null) prootEngine = new PRootEngine();
        String revertCmd = "sed -i -E '/^[[:space:]]*" + module + "_(install|enabled)[[:space:]]*:/d' /etc/iiab/local_vars.yml";
        prootEngine.executeInContainer(this, debianRootfs.getAbsolutePath(), revertCmd, new PRootEngine.OutputListener() {
            @Override public void onOutputLine(String line) { }
            @Override public void onProcessExit(int exitCode) { then.run(); }
            @Override public void onError(String error) { then.run(); }
        });
    }

    private void finishModuleQueue() {
        if (finished) return;
        finished = true;
        persistClearQueue();
        ModuleQueueRepository.get().postDone(new java.util.ArrayList<>(failedModules));
        teardown();
    }

    private void persistQueue() {
        getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE).edit()
                .putString("pending_modules", android.text.TextUtils.join(",", new java.util.ArrayList<>(moduleQueue)))
                .putBoolean("is_batch_installing", true).apply();
    }

    private void persistClearQueue() {
        getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE).edit()
                .putString("pending_modules", "").putBoolean("is_batch_installing", false).apply();
    }

    // ---------------------------------------------------------------- terminal

    private void finishSuccess() {
        if (finished) return;
        finished = true;
        // ADFA-4811: clear the install guard BEFORE publishing SUCCESS, so the UI observer can
        // start the server for this session (handleServerLaunchClick refuses while the guard is set).
        org.iiab.controller.InstallGuard.end(this);
        if (!resetMode && !moduleMode) {
            // ADFA-4466 Phase 1: operational analytics (no-op unless the operator opted in).
            org.iiab.controller.analytics.AnalyticsClient.with(this)
                    .logInstallCompleted(tier != null ? tier.name() : null, true);
        }
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
            if (restContentClient != null) restContentClient.cancel();   // ADFA-4840
        } catch (Exception ignored) {
        }
        if (moduleMode) {
            persistClearQueue();
            ModuleQueueRepository.get().postDone(
                    failedModules != null ? new java.util.ArrayList<>(failedModules) : new java.util.ArrayList<>());
        } else {
            InstallProgressRepository.get().postFailed(getString(R.string.install_msg_cancelled));
        }
        teardown();
    }

    private void teardown() {
        // ADFA-4811: clear the durable install marker on a clean terminal (success/fail/cancel).
        // A process killed mid-install skips this, intentionally leaving the marker set.
        org.iiab.controller.InstallGuard.end(this);
        releaseHardwareLocks();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // Safety net: if the process is torn down without a clean terminal, do not
        // leave the repository stuck in a running state.
        if (!finished) {
            if (moduleMode) ModuleQueueRepository.get().postIdle();
            else InstallProgressRepository.get().postIdle();
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
        // ADFA-4640: capture into the app-scoped log (same process) so install/Ansible
        // output is never lost regardless of which fragment/tab is on screen.
        org.iiab.controller.LogRepository.get().append(line);
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
