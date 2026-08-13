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
    /** ADFA-5119: stop the transfer, keep the partial file and the tier/wishlist decision. */
    public static final String ACTION_PAUSE = "org.iiab.controller.INSTALL_PAUSE";
    /** ADFA-5119: pick the paused transfer back up from its control file. */
    public static final String ACTION_RESUME = "org.iiab.controller.INSTALL_RESUME";
    /**
     * ADFA-5119: somebody is looking at the screen — stop the clock on the held window.
     *
     * <p>Sent by the gate on the first touch while the download is held. The service cannot observe
     * this for itself: presence is a UI fact, and the phase it decides is a service fact, so the fact
     * travels and the decision stays where the state lives.
     */
    public static final String ACTION_USER_PRESENT = "org.iiab.controller.INSTALL_USER_PRESENT";
    // Per-module install queue (ADFA-4476 slice 3): distinct from the rootfs ACTION_START.
    public static final String ACTION_START_MODULES = "org.iiab.controller.INSTALL_START_MODULES";
    /** ADFA-5011: rebuild the dash-node REST core in place (no rootfs rebuild). Reuses this service's
     *  guard/foreground/status-window so the op can't be killed mid-rebuild. */
    public static final String ACTION_REBUILD_DASHBOARD = "org.iiab.controller.REBUILD_DASHBOARD";

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

    // ADFA-4900: per-layer maps config carried with a module queue of {"maps"}. When present, the
    // "maps" module writes the full maps_* var set to local_vars (from the wizard selection) before
    // runrole, instead of the generic <key>_install/_enabled echo. Values are validated against a
    // fixed allowlist before interpolation (D2).
    public static final String EXTRA_MAPS_VECTOR = "mapsVector";   // nat-z8 | 11 | 14
    public static final String EXTRA_MAPS_SAT = "mapsSat";         // 7|9|11|13 | none
    public static final String EXTRA_MAPS_TERRAIN = "mapsTerrain"; // 7|8|9|10 | 0-none
    public static final String EXTRA_MAPS_SEARCH = "mapsSearch";   // boolean: static pop-1k-cities on/off

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // ADFA-5119 (review): volatile, because doPause/doResume/doCancel read these on the main thread
    // while the pipeline threads assign them. The same reason `cancelled` and `stopRequest` are
    // volatile; leaving two of the four unmarked reads as a finished job.
    private volatile Aria2Manager aria2Manager;
    private PRootEngine prootEngine;
    /**
     * ADFA-5119 (review): the live extractor, so a cancellation can stop it.
     *
     * <p>It used to be created inline and dropped, and {@code stopExtraction()} had no caller
     * anywhere in the repo — so Cancel during an extract deleted the rootfs directory while tar was
     * still writing it, and the surviving tree was then adopted by the next install's
     * non-destructive guard. Exactly the failure the cleanup claims to prevent.
     */
    private volatile TarExtractor extractor;
    private volatile org.iiab.controller.content.RestContentClient restContentClient;   // ADFA-4840 (was socket.io, ADFA-4832); volatile: ADFA-5119 review

    private volatile boolean cancelled = false;
    private volatile boolean finished = false;
    private volatile boolean started = false;

    /**
     * ADFA-5119: which kind of work is running, for the one question Cancel has to answer — does
     * abandoning it leave the device with no system (see {@code AbandonedInstall}).
     *
     * <p>Volatile because {@code doCancel()} reads it on the main thread while the pipeline threads
     * write it. It starts at {@code CONTENT}, the harmless answer, and is only ever narrowed to
     * {@code ROOTFS_BUILD} at the one point in the pipeline where it becomes certain that no usable
     * system is left — see {@link #startRootfsDownload()}.
     */
    /** ADFA-5119: automatic attempts made in the open before the decision goes to the user. */
    private static final int RETRY_ATTEMPTS = 3;
    /** ADFA-5119: how long a held download waits for a person before failing through to recovery. */
    private static final long HELD_WINDOW_MS = 60_000L;

    private volatile int softAttempts = 0;
    private volatile org.iiab.controller.download.domain.Aria2Exit.Kind lastStopKind =
            org.iiab.controller.download.domain.Aria2Exit.Kind.UNKNOWN;
    private final Handler heldHandler = new Handler(Looper.getMainLooper());
    private Runnable heldExpiry;   // main thread only

    private volatile org.iiab.controller.install.domain.AbandonedInstall.Work work =
            org.iiab.controller.install.domain.AbandonedInstall.Work.CONTENT;

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

    // ADFA-4900: wizard maps per-layer config (only set when the queue is {"maps"} from the wizard).
    private boolean hasMapsConfig;
    private String mapsVector, mapsSat, mapsTerrain;
    private boolean mapsSearchOn;

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
        // ADFA-5119: pause and resume are not starts. They arrive while a pipeline is already
        // running, so they must not fall through to the `started` guard below — and they must not
        // stop the service: it stays in the foreground precisely so the notification survives a
        // pause and resume remains reachable from it.
        if (ACTION_PAUSE.equals(action)) {
            doPause();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            doResume();
            return START_NOT_STICKY;
        }
        if (ACTION_USER_PRESENT.equals(action)) {
            // ADFA-5119: the window only exists to protect an install nobody is watching. Somebody is
            // watching, so it goes, and the held state now lasts as long as they need — taking the
            // choice away mid-thought would be worse than the stall it was guarding against.
            if (heldExpiry != null) {
                Log.i(TAG, "held window dropped: the user is on the screen");
                cancelHeldWindow();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_REBUILD_DASHBOARD.equals(action)) {
            if (started) return START_NOT_STICKY;
            started = true;
            rebuildMode = true;
            org.iiab.controller.InstallGuard.begin(this);   // exclusive: no concurrent proot op
            iiabRootDir = new File(getFilesDir(), "rootfs");
            debianRootfs = new File(iiabRootDir, "installed-rootfs/iiab");
            if (prootEngine == null) prootEngine = new PRootEngine();
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.k2go_dash_rebuilding)));
            acquireHardwareLocks();
            // ADFA-5011: tag posts as REBUILD so SetupProgressActivity treats this as a blocking rebuild
            // session (stays on the animation, no premature "nothing to do → redirect").
            InstallProgressRepository.get().beginRebuild();
            InstallProgressRepository.get().postProvisioning(getString(R.string.k2go_dash_rebuilding));
            new Thread(this::runDashboardRebuild, "dash-rebuild-service").start();
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
        // ADFA-5119: a module queue runs over a system that exists and keeps running, so abandoning
        // it must not touch the tier, the wishlists or setup_complete.
        if (isModules) work = org.iiab.controller.install.domain.AbandonedInstall.Work.MODULE_QUEUE;
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

            // ADFA-4900: pick up the wizard maps per-layer selection, if any.
            mapsVector = intent.getStringExtra(EXTRA_MAPS_VECTOR);
            mapsSat = intent.getStringExtra(EXTRA_MAPS_SAT);
            mapsTerrain = intent.getStringExtra(EXTRA_MAPS_TERRAIN);
            mapsSearchOn = intent.getBooleanExtra(EXTRA_MAPS_SEARCH, false);
            hasMapsConfig = mapsVector != null && moduleQueue.contains("maps");

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
            // ADFA-5119: the reset route owns what happens after its own cancel; see the RESET note
            // in AbandonedInstall for why this one is deliberately left out of the new cleanup.
            work = org.iiab.controller.install.domain.AbandonedInstall.Work.RESET;
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
            // ADFA-5119 (review): a fresh install — nothing on disk — is already building the system
            // that does not exist, and that is true BEFORE the preflight below, not after. The
            // storage refusal is the likeliest hard failure of all, and with `work` left at CONTENT
            // it cleared the install marker and lifted the gate onto an empty library: the very case
            // this ticket closes, reached by the commonest route.
            //
            // A reinstall is deliberately NOT narrowed here. Its old rootfs is still intact and still
            // bootable until wipeAndInstall() runs, so a refusal on that path must stay recoverable
            // by simply opening the app again. It is narrowed there instead, after the wipe.
            if (!debianRootfs.exists() || !debianRootfs.isDirectory()) {
                work = org.iiab.controller.install.domain.AbandonedInstall.Work.ROOTFS_BUILD;
            }
            // ADFA-5105: from here on the pipeline extracts a rootfs (and, on reinstall, wipes the
            // existing one first). Refuse before any destructive step when it plainly won't fit —
            // UNKNOWN free space refuses too (a wipe that then runs out of disk leaves no bootable OS).
            if (!ensureSpaceForRootfs()) return;
            if (reinstall && debianRootfs.exists() && debianRootfs.isDirectory()) {
                // ADFA-5023: a reinstall wipes the LIVE rootfs. Doing rm -rf while the server proot is up
                // is the corruption the legacy reset/delete guarded against (they refused when alive). If a
                // server is running, quiesce it (pdsm stop) FIRST, then wipe + download; the stop is async
                // so we continue in wipeAndInstall() from its callback. Server down (recovery path) → wipe now.
                if (org.iiab.controller.ServerStateRepository.get().current().alive) {
                    postProvisioning(getString(R.string.server_shutting_down));
                    if (prootEngine == null) prootEngine = new PRootEngine();
                    prootEngine.executeInContainer(this, debianRootfs.getAbsolutePath(),
                            "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm stop'",
                            new PRootEngine.OutputListener() {
                                @Override public void onOutputLine(String line) { log("[Reinstall] pdsm stop: " + line); }
                                @Override public void onProcessExit(int exitCode) { wipeAndInstall(); }
                                @Override public void onError(String error) { log("[Reinstall] pdsm stop error (continuing): " + error); wipeAndInstall(); }
                            });
                    return;   // continues in wipeAndInstall()
                }
                wipeAndInstall();
                return;
            }
            // Fresh install (no rootfs present) — nothing to wipe or stop.
            if (cancelled) return;
            persistInstalledTier();
            startRootfsDownload();
        } catch (Exception e) {
            Log.e(TAG, "Install pipeline crashed", e);
            org.iiab.controller.analytics.AnalyticsClient.with(this).logInstallFailed("download", "exception");
            fail(getString(R.string.install_error_download, String.valueOf(e.getMessage())));
        }
    }

    /** ADFA-5023: wipe the existing rootfs then start the fresh download. Split out so a reinstall over a
     *  live system can run this AFTER the async pdsm stop completes (server proot no longer writing). */
    private void wipeAndInstall() {
        if (cancelled) return;
        // ADFA-5070: the system about to be destroyed is what the content sessions
        // describe. Only the sessions — the orders are kept on this route, because
        // the wizard cleared them on entry and the user has just refilled them for
        // the system this is about to create.
        org.iiab.controller.system.data.ContentStateInvalidator.replacementStarting(this,
                org.iiab.controller.system.domain.SystemReplacement.Cause.REINSTALL);
        postProvisioning(getString(R.string.install_status_wiping_old));
        try {
            ProcessRunner.Result wipe = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
            if (!wipe.isSuccess()) {
                Log.w(TAG, "rm -rf rootfs (reinstall) failed (exit " + wipe.exitCode + "): " + wipe.output);
            }
        } catch (Exception e) {
            Log.w(TAG, "rm -rf rootfs (reinstall) failed", e);
        }
        if (cancelled) return;
        persistInstalledTier();
        startRootfsDownload();
    }

    private void startRootfsDownload() {
        // ADFA-5119: the one place where "there is no usable system" becomes certain. Both callers
        // reach here only after that is true — a fresh install found no rootfs, and a reinstall has
        // already wiped the old one — so this is where Cancel earns the right to be destructive.
        // Setting it earlier would catch a reinstall during its pdsm stop, while the old system is
        // still intact and still bootable.
        work = org.iiab.controller.install.domain.AbandonedInstall.Work.ROOTFS_BUILD;
        String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "armeabi-v7a" : "arm64-v8a";
        String tierString = tier.name().toLowerCase(Locale.US);
        String directUrl = "https://iiab.switnet.org/android/rootfs/latest_" + tierString + "_" + archSuffix + ".meta4";

        if (aria2Manager == null) aria2Manager = new Aria2Manager();
        aria2Manager.startDownload(this, directUrl, new Aria2Manager.DownloadListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta) {
                // Still live, and not a leftover: Aria2NetworkProfiler reports through this form
                // ("Test IPv4", "Test IPv6", then the winner) before the real transfer begins, so
                // an empty override here would blank the status line for the whole probe. It has
                // no rate and no estimate to carry — those only exist once aria2 is transferring.
                if (cancelled) return;
                // ADFA-5119: the attempt counter travels with every tick of a retried download.
                // This override is the profiler's path — "Test IPv4", "Test IPv6" — and the profiler
                // runs again at the start of each attempt, so without carrying the note here the
                // count is wiped exactly when the user needs it: while the same three probes scroll
                // past for the third time with nothing to say which time it is.
                InstallProgressRepository.get().postDownloading(percentage, speed, "", attemptNote());
                updateNotification(getString(R.string.install_status_os_download, percentage, speed));
            }

            @Override
            public void onProgress(int percentage, String speed, String eta,
                                   long bytesPerSecond, long etaSeconds) {
                if (cancelled) return;
                // ADFA-4895: no smoothing here, deliberately, and it is not an oversight — a first
                // pass used EtaSmoother and the label froze at "about 26 min" for half a download
                // that took sixty seconds. Two reasons, and the second is the one that matters:
                // the smoother adopts its first reading immediately, which lands while aria2 is
                // still ramping and is the worst estimate of the whole run; and its dwell only
                // advances while the value holds steady, so a figure that moves every tick resets
                // the window forever and nothing ever replaces that first reading.
                //
                // The deeper reason is that this signal does not need it. Extraction wobbles
                // because files decompress at wildly different speeds; a transfer rate over a
                // network is comparatively steady — 34 to 40 MiB/s across a whole run, measured.
                // Fed a steady rate the estimate moves smoothly on its own, and moving is what an
                // estimate is supposed to do.
                // ADFA-5119: bytes are moving again, so the attempt budget is spent and refilled. A
                // transfer that hiccups once an hour over a long download must not arrive at the
                // third hiccup and hand the user a decision about a link that is working.
                if (bytesPerSecond > 0) softAttempts = 0;
                int bucket = org.iiab.controller.install.domain.EtaSmoother.bucketOf(etaSeconds);
                InstallProgressRepository.get().postDownloading(percentage, speed, formatEta(bucket),
                        attemptNote());
                updateNotification(getString(R.string.install_status_os_download, percentage, speed));
            }

            /**
             * ADFA-5119: a pause is not a cancellation and must not tear anything down. The partial
             * file and its control file stay on disk, the guard stays held, and the service stays in
             * the foreground so the notification can offer Resume.
             */
            @Override
            public void onPaused() {
                if (cancelled) return;
                InstallProgressRepository.get().postPaused(
                        InstallProgressRepository.get().current().percent);
                // Nothing is transferring, and a pause can last hours. Holding a wake lock and a
                // high-performance Wi-Fi lock through it would drain the battery for no transfer at
                // all; doResume() takes them again.
                releaseHardwareLocks();
                // The percentage stays, the rate goes — nothing is moving, so there is no rate to
                // report. Composed rather than a new format string: one less thing to translate for a
                // file that is not supposed to survive this PR.
                updateNotification(getString(R.string.k2go_dl_paused_notif) + "  ·  "
                        + InstallProgressRepository.get().current().percent + "%");
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

            /**
             * ADFA-5119: a dropped transfer is not the end of the install.
             *
             * <p>This is the transition the state model was missing. DOWNLOADING went straight to
             * FAILED, FAILED is terminal, the gate lifts on a terminal — so by the time anything
             * could have offered a retry the user was already looking at a library with no system.
             * The fix is not a button, it is not going terminal in the first place.
             *
             * <p>Only the kinds where the bytes on disk are still worth something come here, which
             * is what makes Retry honest: it continues from the control file rather than starting
             * over. UNKNOWN is included on purpose — aria2's own code 1 covers transient conditions
             * as often as real ones, and the two mistakes are not equal. Offering a retry that fails
             * again costs one tap; treating a recoverable stop as permanent costs the whole download.
             */
            @Override
            public void onError(String error,
                                org.iiab.controller.download.domain.Aria2Exit.Kind kind) {
                if (cancelled || finished) return;
                lastStopKind = kind;
                if (!continuableAfter(kind)) {
                    onError(error);
                    return;
                }
                org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this)
                        .logInstallFailed("download", "soft_" + kind.name().toLowerCase(Locale.US));
                softFail(kind, error);
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
        // ADFA-5118: the archive-listing pass is now the determinate VERIFY phase of the unified bar.
        // Start indeterminate (-1) until the first byte lands; the gzip feeder then drives real % + ETA.
        InstallProgressRepository.get().postVerifying(-1, "", "");
        updateNotification(getString(R.string.install_status_extracting));

        File downloadDir = new File(downloadPath);
        File[] archives = downloadDir.listFiles((dir, name) -> name.endsWith(".tar.xz") || name.endsWith(".tar.gz"));
        if (archives == null || archives.length == 0) {
            org.iiab.controller.analytics.AnalyticsClient.with(this).logInstallFailed("download", "no_archive");
            fail(getString(R.string.install_error_no_archive));
            return;
        }

        File downloadedArchive = archives[0];
        // ADFA-5119 (review): keep the handle. Created inline and dropped, a cancellation had no way
        // to stop tar, so the cleanup's rm -rf raced a process still writing the tree.
        extractor = new TarExtractor();
        extractor.startExtraction(this, downloadedArchive.getAbsolutePath(), iiabRootDir.getAbsolutePath(),
                new TarExtractor.ExtractionListener() {
                    // ADFA-5118: once byte-based progress arrives (gzip path), it owns the unified bar;
                    // the member-count callback is only the fallback for a non-gzip archive.
                    private volatile boolean byteSeen = false;
                    // ADFA-5118: the ETA is shown only during EXTRACT. A countdown during VERIFY would
                    // have to guess the (slower, write-bound) extract time and then jump upward at the
                    // handoff, so verify shows bar + % + file only. The smoother debounces the single
                    // extract countdown so its text doesn't flicker at a boundary.
                    private final org.iiab.controller.install.domain.EtaSmoother etaSmoother =
                            new org.iiab.controller.install.domain.EtaSmoother(5000L);

                    @Override
                    public void onExtractPhase(TarExtractor.Phase phase, int passPercent, long etaSeconds, String line) {
                        byteSeen = true;
                        boolean extract = phase == TarExtractor.Phase.EXTRACT;
                        int unified = org.iiab.controller.deploy.domain.ExtractProgress.unifiedPercent(passPercent, extract);
                        if (extract) {
                            int bucket = etaSmoother.smooth(
                                    org.iiab.controller.install.domain.EtaSmoother.bucketOf(etaSeconds),
                                    android.os.SystemClock.elapsedRealtime());
                            InstallProgressRepository.get().postExtracting(unified, line, formatEta(bucket));
                        } else {
                            InstallProgressRepository.get().postVerifying(unified, line, "");   // no countdown during verify
                        }
                    }

                    @Override
                    public void onProgress(int percent, long done, long total, String line) {
                        if (byteSeen) return;   // gzip path drives the unified bar; ignore member % there
                        InstallProgressRepository.get().postExtracting(percent, line);   // non-gzip fallback
                    }

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
                        // ADFA-5105: don't leave the compressed .tar.gz behind on a failed extract —
                        // on success it's deleted below, but a failure used to keep it, silently
                        // holding ~2–3 GB until the next attempt.
                        downloadedArchive.delete();
                        org.iiab.controller.analytics.AnalyticsClient.with(InstallService.this).logInstallFailed("extract", "extract_error");
                        fail(getString(R.string.install_error_extraction, error));
                    }
                });
    }

    /**
     * ADFA-5119 (review): widen {@code work} back before this runs.
     *
     * <p>By the time companion data starts, the rootfs is extracted and usable — so from here a
     * cancellation is content being abandoned, not a system. Left at ROOTFS_BUILD, the notification's
     * Cancel during a ZIM download or the maps runrole would have deleted a working rootfs and
     * cleared setup_complete. Reachable only from the legacy {@code companion=true} start; the K2Go
     * wizard banks content as wishlists and passes false.
     */
    private void startCompanionData() {
        work = org.iiab.controller.install.domain.AbandonedInstall.Work.CONTENT;
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
                text = text.replaceAll("(?m)^maps_vector_(quality|zoom):\\s*.*", "maps_vector_zoom: 11");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 7");
            } else if (tier == InstallationPlanner.Tier.FULL) {
                text = text.replaceAll("(?m)^maps_vector_(quality|zoom):\\s*.*", "maps_vector_zoom: 11");
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
            // ADFA-5105: reset wipes the rootfs and re-extracts a base one. Refuse before the wipe
            // when it won't fit (fail-safe on UNKNOWN free space too).
            if (!ensureSpaceForRootfs()) return;
            // ADFA-5070: stop and forget the downloads before the rootfs they were
            // writing into goes away.
            org.iiab.controller.system.data.ContentStateInvalidator.replacementStarting(this,
                    org.iiab.controller.system.domain.SystemReplacement.Cause.RESET);
            // 1. WIPE
            postProvisioning(getString(R.string.install_status_wiping_old));
            try {
                ProcessRunner.Result wipe = ProcessRunner.run(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                if (!wipe.isSuccess()) {
                    Log.w(TAG, "rm -rf rootfs (reset) failed (exit " + wipe.exitCode + "): " + wipe.output);
                }
                // The system is gone from here on, and no wizard ran before a reset,
                // so any order still pending was placed against what was just wiped.
                org.iiab.controller.system.data.ContentStateInvalidator.replacementSucceeded(this,
                        org.iiab.controller.system.domain.SystemReplacement.Cause.RESET);
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
        // ADFA-4842: a real module runrole (kolibri/calibreweb/…) modifies system packages/config and
        // restarts services, so it must own the rootfs exclusively — stop the server's SERVICES first
        // (pdsm stop) so it never runs alongside a live server writing the same DBs/config (the
        // data-corruption risk). The install index restarts the server after the queue and only then
        // redirects to a live Library. Maps is the exception: it coexists with a live server (adds
        // tiles, doesn't touch the REST core), so a maps-only batch skips the stop entirely — no
        // needless server bounce for the wizard/Get-More maps flow.
        if (cancelled) return;
        boolean anyRealModule = false;
        for (String m : moduleQueue) if (!"maps".equals(m)) { anyRealModule = true; break; }
        if (!anyRealModule) { installNextModule(); return; }
        updateNotification(getString(R.string.server_shutting_down));
        log("[Modules] Stopping server services before runroles (exclusive rootfs)...");
        if (prootEngine == null) prootEngine = new PRootEngine();
        prootEngine.executeInContainer(this, debianRootfs.getAbsolutePath(),
                "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm stop'",
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { log("[Modules] pdsm stop: " + line); }
                    @Override public void onProcessExit(int exitCode) { installNextModule(); }
                    @Override public void onError(String error) {
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

        // ADFA-4900: for the wizard maps flow, write the full per-layer maps_* var set before
        // runrole (the generic <key>_install/_enabled echo can't express quality/off/search).
        final String installCmd = ("maps".equals(nextModule) && hasMapsConfig)
                ? mapsInstallCmd()
                : "sed -i -E '/^[[:space:]]*" + nextModule + "_(install|enabled)[[:space:]]*:/d' /etc/iiab/local_vars.yml && " +
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

    /**
     * ADFA-4900: build the maps runrole command from the wizard's per-layer selection. Translates
     * the selection into the maps role's local_vars (roles/maps/tasks/install_frontend.yml):
     * satellite/terrain "none" turns the layer off; search maps to maps_search_engine +
     * maps_search_static_db. Every var the role's iiab.ini step references is written so the play
     * never hits an undefined var. Values are validated against a fixed allowlist (D2); anything
     * unexpected falls back to a safe default. Uses sed-delete + echo (append-if-missing).
     */
    // ADFA-4900: the maps runrole command is a pure, unit-tested builder (MapsRunroleCommand).
    private String mapsInstallCmd() {
        return org.iiab.controller.install.domain.MapsRunroleCommand.build(
                mapsVector, mapsSat, mapsTerrain, mapsSearchOn);
    }

    private void finishModuleQueue() {
        if (finished) return;
        finished = true;
        persistClearQueue();
        // ADFA-4842: clear the durable install guard BEFORE publishing DONE so the LibraryActivity
        // observer that restarts the server (canStartServer() requires !InstallGuard.inProgress) is not
        // raced by teardown()'s later clear. teardown() clears it again (idempotent).
        org.iiab.controller.InstallGuard.end(this);
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

    // ---------------------------------------------------------------- dashboard rebuild (ADFA-5011)

    /** True while this service is running a dash-node rebuild (skips install-only analytics/finish). */
    private boolean rebuildMode = false;

    /** Drive DashboardRebuildRunner (pdsm stop -> preflight -> rebuild -> pdsm start) on the service's
     *  guarded, foreground lifecycle. Terminal states reuse finishSuccess()/fail() so the guard, the
     *  status window and teardown behave exactly like an install. */
    private void runDashboardRebuild() {
        new org.iiab.controller.redesign.DashboardRebuildRunner(this, prootEngine, debianRootfs.getAbsolutePath())
                .start(new org.iiab.controller.redesign.DashboardRebuildRunner.Callback() {
                    @Override public void onLog(String line) { log(line); }
                    @Override public void onPreflight(org.iiab.controller.redesign.DashboardRebuildRunner.PreflightResult r) {
                        log("[rebuild] preflight ok=" + r.ok + " installed=" + r.installed + " available=" + r.available);
                    }
                    @Override public void onDone() { log("[rebuild] complete"); finishSuccess(); }
                    @Override public void onError(String reason) { log("[rebuild] error: " + reason); fail(reason); }
                });
    }

    // ---------------------------------------------------------------- terminal

    private void finishSuccess() {
        if (finished) return;
        finished = true;
        // ADFA-4811: clear the install guard BEFORE publishing SUCCESS, so the UI observer can
        // start the server for this session (handleServerLaunchClick refuses while the guard is set).
        org.iiab.controller.InstallGuard.end(this);
        if (!resetMode && !moduleMode && !rebuildMode) {
            // ADFA-4466 Phase 1: operational analytics (no-op unless the operator opted in).
            org.iiab.controller.analytics.AnalyticsClient.with(this)
                    .logInstallCompleted(tier != null ? tier.name() : null, true);
        }
        InstallProgressRepository.get().postSuccess();
        teardown();
    }

    /**
     * A failure this operation cannot continue from.
     *
     * <p>ADFA-5119: it no longer clears the install marker when what failed was the rootfs build.
     * Clearing it there is what made a failed first install invisible — the app forgot an install had
     * been running, decided no system was expected, and opened an empty library. A process killed
     * mid-install skips {@link #teardown()} entirely and therefore keeps the marker, so the crash was
     * handled better than the failure. Keeping it makes the two agree, and the recovery path that
     * already exists picks it up: same predicate, same dialog, and its wording — "the setup was
     * stopped before it finished" — is already true for this case.
     */
    private void fail(String message) {
        if (finished) return;
        finished = true;
        InstallProgressRepository.get().postFailed(message);
        teardown(!org.iiab.controller.install.domain.AbandonedInstall.leavesNoSystem(work));
    }

    /**
     * ADFA-5105: destructive-run free-space preflight. This gate runs BEFORE the download, and the
     * pipeline then writes the compressed .tar.gz and the uncompressed tree, which coexist during
     * extraction — so the "needed" is the PEAK (compressed + uncompressed) for this tier+abi, from
     * RootfsCatalog (measured via ADFA-5110 when published, estimate otherwise). Returns true to
     * proceed; on a refusal it reports the shortfall through fail() and returns false so the caller
     * stops before wiping.
     */
    private boolean ensureSpaceForRootfs() {
        if (cancelled) return false;
        org.iiab.controller.rootfs.data.RootfsCatalog cat =
                new org.iiab.controller.rootfs.data.RootfsCatalog(this);
        org.iiab.controller.rootfs.domain.RootfsTier rTier =
                (tier == null) ? org.iiab.controller.rootfs.domain.RootfsTier.BASIC
                               : org.iiab.controller.rootfs.domain.RootfsTier.valueOf(tier.name());
        long needed = cat.peakInstallBytes(rTier, cat.detectAbi());
        org.iiab.controller.storage.FreeSpacePreflight.Result pf =
                org.iiab.controller.storage.FreeSpacePreflight.check(this, needed);
        if (pf.ok) return true;
        fail(getString(R.string.install_error_no_storage) + " ("
                + org.iiab.controller.util.ByteFormatter.toHuman(pf.amountToReport()) + ")");
        return false;
    }

    /**
     * ADFA-5118: resolve a smoothed ETA bucket (see EtaSmoother) to a short localized "time left"
     * string for the unified bar. Empty when unknown, "almost done" for bucket 0 (under a minute),
     * else "about N min left".
     */
    private String formatEta(int bucket) {
        if (bucket == org.iiab.controller.install.domain.EtaSmoother.UNKNOWN) return "";
        if (bucket == 0) return getString(R.string.k2go_eta_almost);
        return getString(R.string.k2go_eta_min, bucket);
    }

    /**
     * ADFA-5119: stop the transfer and keep everything.
     *
     * <p>Deliberately narrow: only a running download can be paused. Pausing an extract or a
     * runrole would mean leaving a rootfs half-written on disk with no way to say so, which is the
     * opposite of what this ticket is for — the state must always be one we can name.
     */
    private void doPause() {
        if (finished || cancelled) return;
        if (!InstallProgressRepository.get().current().isRunning()) return;
        if (InstallProgressRepository.get().current().phase != InstallState.Phase.DOWNLOADING) {
            Log.i(TAG, "pause ignored: only a download can be paused");
            return;
        }
        // ADFA-5119 (review): and only the rootfs download. A scratch reset also reports DOWNLOADING,
        // but its listener does not implement onPaused() — the default is a no-op — so pausing it
        // killed aria2 and left that pipeline with no terminal at all: isRunning() true forever, the
        // marker held, the service alive. The reset route is out of scope for this ticket (see the
        // RESET note in AbandonedInstall), so the honest answer is to refuse rather than to teach a
        // second pipeline how to pause.
        if (work != org.iiab.controller.install.domain.AbandonedInstall.Work.ROOTFS_BUILD) {
            Log.i(TAG, "pause ignored: only the rootfs download can be paused, not " + work);
            return;
        }
        if (aria2Manager != null) aria2Manager.pauseDownload();
        // The state is posted by the listener's onPaused(), not here: aria2 has to actually stop
        // first, and it is the one that knows when that happened.
    }

    /**
     * ADFA-5119: whether a stop of this kind leaves anything worth continuing from.
     *
     * <p>The reading comes from {@code Aria2Exit}; the policy is here, because that class describes
     * and deliberately does not decide — the rootfs path and the content paths are entitled to
     * different answers from the same reading.
     *
     * <p>PERMANENT is excluded because a retry is a lie there: the disk is still full, the mirror
     * still does not have the file. Those go the other way — the abandonment cleanup and back to the
     * choice — which for the commonest of them, a full disk, is not a punishment but the remedy: a
     * smaller tier.
     */
    private static boolean continuableAfter(org.iiab.controller.download.domain.Aria2Exit.Kind kind) {
        return kind == org.iiab.controller.download.domain.Aria2Exit.Kind.TRANSIENT
                || kind == org.iiab.controller.download.domain.Aria2Exit.Kind.STALLED
                || kind == org.iiab.controller.download.domain.Aria2Exit.Kind.UNKNOWN;
    }

    /**
     * ADFA-5119: hold the operation open on a stop we did not choose.
     *
     * <p>Deliberately does NOT set {@code finished} and does NOT call {@link #teardown()}: the
     * install has not ended, so the marker stays, the service stays in the foreground, and Retry and
     * Cancel both stay reachable. What it does release is the hardware locks — a stop like this can
     * sit there as long as a pause, and a wake lock held over nothing drains the battery.
     */
    private void softFail(org.iiab.controller.download.domain.Aria2Exit.Kind kind, String detail) {
        int percent = InstallProgressRepository.get().current().percent;

        // ADFA-5119: try again ourselves first, in the open. aria2's own budget was cut to one try
        // (see Aria2Manager) precisely so this loop could exist where the user can see it: the old
        // five silent retries were the same waiting, spent behind a frozen number.
        if (softAttempts < RETRY_ATTEMPTS) {
            softAttempts++;
            String line = attemptNote();
            Log.w(TAG, "download stopped (" + kind + ") at " + percent + "%: " + detail
                    + " — automatic " + line);
            InstallProgressRepository.get().postRetrying(percent, line);
            updateNotification(line);
            // Straight back in, no backoff. The wait that a backoff buys was already spent inside
            // aria2's own timeout, and the case a delay would help with — the network being down — is
            // answered in a second by the connectivity signal, not by guessing at seconds.
            startRootfsDownload();
            return;
        }

        // Out of attempts. The decision is the user's now, and it stays theirs.
        String line = getString(softFailLine(kind));
        Log.w(TAG, "download held after " + RETRY_ATTEMPTS + " attempts, last stop " + kind
                + " at " + percent + "%: " + detail);
        InstallProgressRepository.get().postSoftFailed(percent, line);
        releaseHardwareLocks();
        updateNotification(line);
        beginHeldWindow();
    }

    /**
     * ADFA-5119: the minute in which the download waits for a person, and what happens if none comes.
     *
     * <p>A held state with no expiry is the dead end this ticket exists to close, wearing different
     * clothes: an unattended install that drops at 3 a.m. would sit on SOFTFAILED forever, holding the
     * marker and a foreground service, and the owner would find it exactly as they left it. So the
     * window runs out and the operation fails to recovery, where there is a decision to make.
     *
     * <p><b>But it is cancelled the moment somebody is actually there.</b> If the user touches the
     * screen the window is dropped and the state holds indefinitely — taking a choice away from
     * someone who is mid-thought would be worse than the stall. That is also why nothing draws a
     * countdown: the only person who could read it is the person whose presence stops it.
     *
     * <p>Lifecycle: started here, cancelled by {@link #ACTION_USER_PRESENT} or by leaving the phase,
     * and it lives in the service rather than the Activity on purpose — the Activity can be paused or
     * gone while the service still owns the phase. If the process is killed inside the window the
     * timer dies with it and the install marker takes over, which is the same recovery this would
     * have routed to.
     */
    /**
     * ADFA-5119: "Reconnecting… 2 of 3", or empty on an ordinary first attempt.
     *
     * <p>One place builds it and every post during a retried download carries it, so it cannot be
     * wiped by whichever path happens to report next — which is what went wrong when it lived on the
     * detail row and the profiler posted over it.
     */
    private String attemptNote() {
        if (softAttempts <= 0) return "";
        return getString(R.string.k2go_dl_attempt, softAttempts, RETRY_ATTEMPTS);
    }

    private void beginHeldWindow() {
        cancelHeldWindow();
        heldExpiry = () -> {
            heldExpiry = null;
            if (finished || cancelled) return;
            if (!InstallProgressRepository.get().current().isSoftFailed()) return;
            Log.i(TAG, "held window expired with nobody watching — failing through to recovery");
            fail(getString(softFailLine(lastStopKind)));
        };
        heldHandler.postDelayed(heldExpiry, HELD_WINDOW_MS);
    }

    private void cancelHeldWindow() {
        if (heldExpiry != null) {
            heldHandler.removeCallbacks(heldExpiry);
            heldExpiry = null;
        }
    }

    /**
     * The user-facing line for a stop we did not choose.
     *
     * <p>Mapped from {@code Kind} rather than passing {@code Aria2Exit.label()} through: that method
     * says outright that it is a stable English phrase for logs, so putting it on screen would ship
     * untranslated text to 35 locales. It names the cause only — the button beside it already says
     * what to do about it.
     */
    private static int softFailLine(org.iiab.controller.download.domain.Aria2Exit.Kind kind) {
        if (kind == org.iiab.controller.download.domain.Aria2Exit.Kind.STALLED) {
            return R.string.k2go_dl_soft_slow;
        }
        if (kind == org.iiab.controller.download.domain.Aria2Exit.Kind.TRANSIENT) {
            return R.string.k2go_dl_soft_lost;
        }
        return R.string.k2go_dl_soft_stopped;
    }

    /**
     * ADFA-5119: pick the download back up. aria2 continues from the control file it kept.
     *
     * <p>One entry point for both labels, because Pause/Resume and a stop/Retry are the same two
     * events in the same order — the only difference is who stopped it, and that difference is
     * already spent by the time we get here.
     */
    private void doResume() {
        if (finished || cancelled) return;
        if (!InstallProgressRepository.get().current().isHeld()) return;
        // ADFA-5119 (review): leave the held state FIRST, and it is not cosmetic. Nothing posted a new
        // state until aria2's first progress line, which is after the metalink fetch and the two
        // six-second stack probes — ten seconds or more in which the button still read "Resume". A
        // second tap passed the guard above and started a second aria2 over the same partial file,
        // overwrote aria2Process so the first became unkillable, and took a second wake lock without
        // releasing the first.
        //
        // Posting the state is the debounce, rather than a private "resuming" flag: isHeld() goes
        // false, so this method's own guard refuses the second tap, and the state model keeps being
        // the single place that knows whether the download is moving. A flag would be a second one.
        InstallProgressRepository.get().postDownloading(
                InstallProgressRepository.get().current().percent, "");
        // A manual retry is a fresh decision, so it gets a fresh budget — and the window it was
        // waiting on has been answered.
        cancelHeldWindow();
        softAttempts = 0;
        acquireHardwareLocks();
        // The URL is derived from the tier and arch fields, so re-entering the download step is all
        // it takes; its reconcile step finds the .aria2 control file and continues from there.
        startRootfsDownload();
    }

    /**
     * ADFA-5119: stop the operation and give up on it.
     *
     * <p>Cancel is not the loud twin of Pause. Pause keeps the partial file and the decision behind
     * it; Cancel gives up both, which is why it is the control that asks for confirmation. What it
     * has to undo depends on what was running — {@code AbandonedInstall} answers that — and only the
     * rootfs build leaves the device with nothing to boot.
     *
     * <p>The other two paths keep their existing behaviour to the letter, including reporting as
     * FAILED with a "cancelled" message. That reads wrong and it is: a cancellation is a choice, not
     * a failure. It is left alone because the legacy screens that render those two branches key off
     * {@code case FAILED} to re-enable their buttons, and correcting the phase without correcting
     * those screens would leave a button stuck mid-progress. The boot gate — the surface this ticket
     * is about — gets the honest phase.
     */
    private void doCancel() {
        if (finished) return;
        cancelled = true;
        finished = true;
        try {
            if (aria2Manager != null) aria2Manager.stopDownload();
            if (restContentClient != null) restContentClient.cancel();   // ADFA-4840
            // ADFA-5119 (review): stop tar BEFORE the cleanup deletes the tree it is writing into.
            // Without this the rm -rf below raced a live extraction and the surviving directory was
            // adopted by the next install's non-destructive guard — a "successful" boot over a wreck.
            if (extractor != null) extractor.stopExtraction();
        } catch (Exception ignored) {
        }
        if (moduleMode) {
            persistClearQueue();
            ModuleQueueRepository.get().postDone(
                    failedModules != null ? new java.util.ArrayList<>(failedModules) : new java.util.ArrayList<>());
            teardown();
            return;
        }
        if (!org.iiab.controller.install.domain.AbandonedInstall.leavesNoSystem(work)) {
            InstallProgressRepository.get().postFailed(getString(R.string.install_msg_cancelled));
            teardown();
            return;
        }
        // The cleanup deletes files and writes preferences, and doCancel runs on the main thread
        // (onStartCommand). The flags above already stopped the pipeline, so the work below can take
        // as long as it needs — and CANCELLED is posted only once it is really done, because the
        // observer navigates on that post and the next screen reads what this writes.
        new Thread(this::forgetTheAbandonedSystem, "install-abandon").start();
    }

    /**
     * ADFA-5119: erase every trace of a system the user decided not to build.
     *
     * <p>Five things claimed that system existed or was about to. Leaving any one of them behind is
     * a specific bug, not untidiness:
     *
     * <ol>
     *   <li><b>The partial tarball and its {@code .aria2} control file.</b> Gigabytes, and the whole
     *       point of the control file is that a later run continues from it — which is right after a
     *       Pause and wrong after a Cancel, where the next run may be a different tier entirely.</li>
     *   <li><b>A half-written rootfs.</b> If the cancel arrived during extraction, the rootfs
     *       directory already exists while being useless, and the next install's non-destructive
     *       guard (ADFA-4725) would see it, skip the extract and boot the wreck.</li>
     *   <li><b>The download sessions and the pending wishlists</b>, through ADFA-5070's one door, so
     *       the content chosen for the tier being given up is not drained into the next one.</li>
     *   <li><b>{@code installed_tier}</b>, which a later "Get more" reads to size content against a
     *       system that was never installed.</li>
     *   <li><b>{@code setup_complete}</b>, which is what decides whether the next launch opens the
     *       library or the wizard. This is the one that keeps the promise: no path ends in the app
     *       with no system.</li>
     * </ol>
     *
     * <p>The install marker is a sixth, and it is already handled — {@link #teardown()} clears it on
     * every clean terminal. Left set, the next launch would open in damaged-system recovery over a
     * system nobody asked for.
     *
     * <p>Deliberately noisy in the log, for the same reason as {@code ContentStateInvalidator}:
     * discarding a user's gigabytes and their choices is not a detail, and the log is the only record
     * of it there is.
     */
    private void forgetTheAbandonedSystem() {
        Log.i(TAG, "install abandoned by the user — discarding the partial download and the decision");
        try {
            // 1. The transfer. The whole directory: aria2 leaves the tarball, the control file and
            // the network profiler's probe files in it, and it is recreated on the next download.
            File downloads = (iiabRootDir != null) ? new File(iiabRootDir, "downloads")
                                                   : new File(getFilesDir(), "rootfs/downloads");
            if (downloads.exists()) {
                ProcessRunner.Result rm = ProcessRunner.run(
                        new String[]{"rm", "-rf", downloads.getAbsolutePath()});
                if (!rm.isSuccess()) Log.w(TAG, "could not remove the partial download: " + rm.output);
            }

            // 2. A rootfs that only got half written. By the time `work` is ROOTFS_BUILD there is no
            // system worth keeping, so anything here is wreckage by definition.
            if (debianRootfs != null && debianRootfs.exists()) {
                ProcessRunner.Result rm = ProcessRunner.run(
                        new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                if (!rm.isSuccess()) Log.w(TAG, "could not remove the partial rootfs: " + rm.output);
            }

            // 3. Sessions and orders, through the single door (ADFA-5070/5074). Enumerating the
            // stores here instead is exactly how the module ones came to be missed once already.
            org.iiab.controller.system.data.ContentStateInvalidator.replacementStarting(this,
                    org.iiab.controller.system.domain.SystemReplacement.Cause.ABANDONED_INSTALL);
            org.iiab.controller.system.data.ContentStateInvalidator.replacementSucceeded(this,
                    org.iiab.controller.system.domain.SystemReplacement.Cause.ABANDONED_INSTALL);

            // 4 + 5. The decision itself, and last on purpose. If the process is killed part-way
            // through this method, everything above it is disposable wreckage and the install marker
            // is still set, so the next launch enters damaged-system recovery — which offers a
            // reinstall. Clearing setup_complete first and dying here would instead send the user to
            // the wizard while the marker still says an install is running.
            //
            // commit(), not apply(): the state posted below sends the UI
            // to the tier selection immediately, and a later cold launch reads setup_complete to
            // decide between the library and the wizard. An asynchronous write is a race with both.
            //
            // We are the first writer to set setup_complete false — the other four only ever set it
            // true, which is why an abandoned install used to strand the user on an empty library.
            getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                    .edit()
                    .remove("installed_tier")
                    .putBoolean(getString(R.string.pref_key_setup_complete), false)
                    .commit();
        } catch (Exception e) {
            // Never leave the UI waiting because a cleanup step failed: the state below is what
            // releases the boot gate, so it is posted either way and the failure is logged.
            Log.w(TAG, "abandon cleanup did not complete", e);
        }
        InstallProgressRepository.get().postCancelled();
        teardown();
    }

    private void teardown() {
        teardown(true);
    }

    /**
     * @param clearMarker ADFA-4811 clears the durable install marker on a clean terminal, because a
     *                    process killed mid-install skips this method and the marker left behind is
     *                    what tells the next launch to stand back. ADFA-5119 adds the one case where
     *                    a clean terminal must behave like the kill: a failed rootfs build ends with
     *                    no system, so forgetting that an install happened is exactly what let the
     *                    app open an empty library. Success, cancellation and every module or reset
     *                    path still pass true — a cancellation has already removed the residue and
     *                    cleared setup_complete, so it needs no marker to be recovered from.
     */
    private void teardown(boolean clearMarker) {
        cancelHeldWindow();   // ADFA-5119: nothing to wait for once this is over
        if (clearMarker) {
            org.iiab.controller.InstallGuard.end(this);
        } else {
            Log.i(TAG, "install marker kept: this failure leaves no system, so the next launch"
                    + " must offer recovery rather than an empty library");
        }
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
        // ADFA-4919: return to the modern progress surface — LibraryActivity shows rootfs progress
        // (boot gate) and routes to the proot install index when a module is running — unlike legacy
        // MainActivity, which shows neither. Reusable for any proot module install (delivery, etc.).
        Intent open = new Intent(this, org.iiab.controller.redesign.LibraryActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.install_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true);

        // ADFA-5119: the rootfs download gets Pause/Resume here and NO Cancel.
        //
        // Cancel was the only action this notification ever had, and on this path it was the wrong
        // one twice over: it is destructive — it discards the transfer, the tier and the wishlists —
        // and it fired straight from the shade with no confirmation, while the same decision on
        // screen asks first. A control that expensive should not be one stray tap in a crowded
        // shade, so it stays where it can be confirmed. The notification keeps the cheap, reversible
        // half; the notification body already carries the percentage and the rate, and loses the
        // rate on a pause because there is no longer one to report.
        //
        // Nothing is offered during verify, extract or provisioning: a pause there would leave a
        // half-written rootfs, and Cancel is exactly what we just took away. The notification's job
        // in those phases is to report, and its tap still opens the screen where the choice lives.
        //
        // Every other user of this notification — the module queue, the scratch reset, the dashboard
        // rebuild — keeps Cancel unchanged. They have no pause to offer and Cancel is their only
        // control.
        // Keyed on the pipeline, not on `work`. `work` only narrows to ROOTFS_BUILD once the pipeline
        // has decided, and this notification is posted before that — so keying on it would show
        // Cancel for the first second of every install, which is one stray tap in the shade doing
        // the exact destructive thing this change removes.
        InstallState st = InstallProgressRepository.get().current();
        boolean installPipeline = !moduleMode && !resetMode && !rebuildMode;
        if (installPipeline && st.isHeld()) {
            b.addAction(0, getString(st.isSoftFailed() ? R.string.k2go_dl_retry
                                                       : R.string.k2go_dl_resume),
                    serviceAction(ACTION_RESUME, 2));
        } else if (installPipeline && st.phase == InstallState.Phase.DOWNLOADING) {
            b.addAction(0, getString(R.string.k2go_dl_pause), serviceAction(ACTION_PAUSE, 3));
        } else if (!installPipeline) {
            b.addAction(0, getString(R.string.install_notif_cancel),
                    serviceAction(ACTION_CANCEL, 1));
        }
        return b.build();
    }

    /**
     * A notification action that delivers one of our own intents.
     *
     * <p>Distinct request codes per action, deliberately: {@code PendingIntent} identity ignores the
     * action string, so reusing one code would let FLAG_UPDATE_CURRENT hand the same pending intent a
     * different action — a Pause button that cancels.
     */
    private PendingIntent serviceAction(String action, int requestCode) {
        Intent i = new Intent(this, InstallService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateNotification(String text) {
        if (finished) return;
        // ADFA-4919: update via startForeground (like a fresh foreground post), NOT
        // NotificationManager.notify(). notify() re-posts the notification as a regular one and drops
        // the foreground-service protection (FLAG_NO_CLEAR), which let the user SWIPE the install
        // notification away mid-proot-install. Re-asserting startForeground keeps it the FGS
        // notification -> ongoing / non-dismissible, matching the terminal's protected notification.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (finished) return;
            startForeground(NOTIFICATION_ID, buildNotification(text));
        });
    }
}
