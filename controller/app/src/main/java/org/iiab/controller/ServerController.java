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


import android.content.Context;
import android.os.Handler;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import org.iiab.controller.util.AppExecutors;

import java.io.File;

public class ServerController {

    private static final String TAG = "IIAB-ServerController";
    private static final int CHECK_INTERVAL_MS = 3000;
    /**
     * ADFA-5103 / ADFA-5343a (D1): how long the services may be <b>continuously observed down</b> (proot
     * present) before ensure-up escalates from "still coming up / pdsm will respawn it" to "stuck →
     * relaunch". Timed from the service drop, not the proot's age (ADR-5343a): a mature proot whose
     * dash-node blips stays well under this and self-heals via pdsm, while a boot — services down since
     * the proot started — is still protected for this long (comfortably over the 3.5 s mid-boot window
     * that got the earlier kill reverted, and over a normal boot-to-services time).
     */
    private static final long SERVICE_DOWN_GRACE_MS = 20_000L;

    /** Activity-side callbacks the server lifecycle needs. */
    public interface Host {
        void addToLog(String message);
        void startFusionPulse();
        void stopBtnProgress();
        void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn);
        void refreshServerUi();
        Boolean getTargetServerState();
        void setTargetServerState(Boolean target);
        void enableSystemProtection();
        void disableSystemProtection();
        /** ADFA-4837: a start has begun. Fires immediately (before any pdsm output) so the boot
         *  screen can show an animated "starting" message during the long silent warm-up, instead of
         *  a blank line until the first pdsm service reports ~15s later. */
        default void onStartupBegan() {}
        /** ADFA-4837: the pdsm service currently starting, for a boot progress line (symmetric to
         *  onShutdownProgress). Heavy services (kolibri/kiwix) warm up lazily after this. */
        default void onStartupProgress(String service) {}
    }

    private final AppCompatActivity activity;
    private final Host host;
    private final Preferences prefs;

    public PRootEngine serverEngine;
    private boolean isWifiActive = false;
    private boolean isHotspotActive = false;
    // ADFA-5103: an ensure-up decision or launch is in flight. Because the decision now runs off the
    // main thread, two concurrent startEnvironment() calls could each read /proc, both see nothing,
    // and both LAUNCH — the synchronous main-thread serialisation that used to prevent that is gone.
    // This flag restores it: a concurrent call is a no-op until the launch (or the no-op) resolves.
    private volatile boolean ensuring = false;
    // ADFA-5343a (D1): the last liveness snapshot from the poll, threaded so servicesDownSinceMs
    // measures CONTINUOUS observed downtime (reset on an observation gap). Read by the ensure-up
    // decision to key the kill on service downtime, not proot age.
    //
    // ADFA-5343 (Phase 2): TWO writers, not one — the poll advances it, and doLaunchEnvironment resets
    // it to null when a fresh proot starts (so the new proot gets its full grace, not the old one's
    // inherited downtime). They are serialised by livenessLock: the poll reads prev and writes the next
    // snapshot atomically under the lock (AFTER probing, so the ~2.5s network probe never holds it), and
    // the boot reset takes the same lock — so a reset can never be clobbered by a poll that had read prev
    // before it. Still volatile, for the lock-free read on the ensure-up decision path.
    private volatile org.iiab.controller.env.domain.ServerLiveness lastLiveness;
    /** Guards the read-prev-then-write of {@link #lastLiveness} against the boot-time reset (Phase 2). */
    private final Object livenessLock = new Object();
    private static final java.util.regex.Pattern PDSM_SVC = java.util.regex.Pattern.compile("\\[pdsm:([^\\]]+)\\]");

    private final Handler timeoutHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private final Handler serverCheckHandler = new Handler(android.os.Looper.getMainLooper());
    // ADFA-5343 (Phase 4d-2): connectivity-only poll now — server liveness moved to the reconciler tick,
    // the single driver + publisher. This runnable just keeps the Wi-Fi/hotspot LEDs fresh.
    private final Runnable serverCheckRunnable = new Runnable() {
        @Override
        public void run() {
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

    public boolean isWifiActive() { return isWifiActive; }
    public boolean isHotspotActive() { return isHotspotActive; }

    // ADFA-5343 (Phase 4d-2): the server-liveness poll (checkServerStatus), the log-only reconciler seam,
    // the targetServerState transition, and the server-uptime analytics all moved to the reconciler's tick
    // — the single liveness capture + publisher + actuator. The bridge (Actuator/setActuator) is gone too.

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
        // ADFA-4957: single implementation lives in EnvironmentControl so the deep-op foreground
        // service can write the same fake /proc data when it boots the environment off-UI.
        org.iiab.controller.env.EnvironmentControl.createFakeSysData(rootfsDir);
    }

    // --- server start / stop (the control button) -------------------------------

    /**
     * ADFA-4842: UNCONDITIONAL, deterministic boot of the Debian/proot environment (pdsm start).
     *
     * <p><b>Why this exists — DO NOT replace calls to it with the toggle {@link #handleServerLaunchClick}.</b>
     * A proot MODULE install runs each runrole in its own proot with {@code --kill-on-exit}: the role does a
     * clean start → its tasks → clean stop, and when the runrole's proot exits it also kills any service it
     * (re)started. So right after a runrole finishes, the environment is DOWN — that is the intended, clean
     * per-module cycle (start → work → stop), especially when several modules install in series. Only after
     * the LAST module do we bring the environment back up, and that job belongs to the install index.
     *
     * <p>The catch: the app's cached {@link ServerStateRepository} {@code alive} can still read TRUE for a
     * moment (the 3s poll hasn't seen the runrole proot exit yet). {@code handleServerLaunchClick} is a
     * TOGGLE — starts if {@code !alive}, STOPS if {@code alive} — so on that stale TRUE it would STOP instead
     * of start. That is exactly the bug we chased: the index logged "Stopping IIAB environment gracefully"
     * right after DONE and landed on a dead Home. The index KNOWS the environment must come up now, so it
     * starts UNCONDITIONALLY here — never via the toggle.
     *
     * <p>REST content installs are a different world: they run on the LIVE server (it never goes down), so
     * none of this applies there. This method is only for the post-module boot driven by the index.
     */
    public void startEnvironment() {
        if (ensuring) return;   // ADFA-5103: a decision/launch is already in flight; never double-launch
        ensuring = true;
        // ADFA-5103: "ensure it is up", decided OFF the main thread — this has six callers, all on it,
        // one of them a tap, and the decision reads /proc. Launch if nothing of ours is running; a
        // no-op if it is already up or still inside its boot grace; kill+relaunch only a stuck orphan
        // (alive, services down, past the grace). A proot cannot be re-entered, so a live-but-
        // serviceless environment is recovered by ending it and booting a fresh one — but never one
        // still in its boot grace, which was the 3.5 s mid-boot kill that got the earlier attempt
        // reverted. EnvironmentProcess is the detection half; EnvironmentEnsure is the decision,
        // pure and unit-tested on the JVM. `ensuring` is cleared by doLaunchEnvironment() on the
        // launch paths and here on the no-op paths, so it is released exactly once.
        AppExecutors.get().io().execute(() -> {
            long now = android.os.SystemClock.elapsedRealtime();
            boolean envAlive = org.iiab.controller.env.EnvironmentProcess.isRunning(activity);
            // ADFA-5280: decide on FRESH liveness, not the cached ServerStateRepository.alive. Right
            // after a module batch's `pdsm stop`, the cache still reads TRUE until the 3s poll catches
            // up, so a stale read returned NOOP_HEALTHY and the box was never relaunched (Home
            // "Couldn't start" until a manual Retry). A live probe reads a just-stopped server as down
            // at once; a genuinely-healthy env still answers true -> NOOP_HEALTHY, so this never
            // double-boots. Safe here: this block already runs off the main thread.
            boolean servicesAlive = org.iiab.controller.redesign.RestReadiness.apiReady();
            // ADFA-5343a (D1): escalate on SERVICE downtime, not proot age. The continuous-downtime clock
            // lives in the one liveness source (threaded by the poll); a stale/absent snapshot reports
            // -1, which decide() treats as "wait, do not kill". A mature proot whose dash-node just
            // blipped is a small downtime -> WAIT (pdsm respawns, ~3s); only a service down past the
            // grace is a stuck environment worth relaunching.
            org.iiab.controller.env.domain.ServerLiveness ll = lastLiveness;
            long servicesDownMs = (ll == null) ? -1L
                    : ll.servicesDownMs(now, org.iiab.controller.env.domain.ServerLiveness.DEFAULT_FRESH_MS);
            org.iiab.controller.env.domain.EnvironmentEnsure.Action action =
                    org.iiab.controller.env.domain.EnvironmentEnsure.decide(
                            envAlive, servicesAlive, servicesDownMs, SERVICE_DOWN_GRACE_MS);
            switch (action) {
                case LAUNCH:
                    activity.runOnUiThread(this::doLaunchEnvironment);
                    break;
                case KILL_AND_RELAUNCH:
                    android.util.Log.i(TAG, "ADFA-5343a: services down " + servicesDownMs + "ms (past the"
                            + " grace) on a live proot — reclaiming the orphaned environment and relaunching");
                    org.iiab.controller.env.EnvironmentProcess.killOrphan(activity);
                    activity.runOnUiThread(this::doLaunchEnvironment);
                    break;
                case NOOP_HEALTHY:
                case WAIT_BOOT_GRACE:
                default:
                    android.util.Log.i(TAG, "ADFA-5103: ensure-up is a no-op (" + action
                            + ", servicesDown " + servicesDownMs + "ms) — not stacking a second proot");
                    ensuring = false;
                    break;
            }
        });
    }

    /**
     * ADFA-5103: the actual boot, on the main thread. Reached only from {@link #startEnvironment()}
     * once it has decided off-thread that a fresh proot is needed — either nothing of ours was
     * running, or a stuck orphan was just killed. This is the old body of {@code startEnvironment},
     * unchanged except that its {@code onStartupBegan} no longer needs {@code runOnUiThread} (it is
     * already there) and it records the environment as coming up.
     */
    private void doLaunchEnvironment() {
        File rootfsDir = new File(activity.getFilesDir(), "rootfs/installed-rootfs/iiab");
        host.addToLog(activity.getString(R.string.log_server_booting_native));
        host.onStartupBegan();   // ADFA-4837: fill the pre-pdsm silent window
        // ADFA-5343a (D1): a fresh environment is starting — restart the service-downtime clock so the
        // new proot gets its full boot grace. Without this a KILL_AND_RELAUNCH keeps the accumulated
        // downtime and re-kills the booting proot every tick, before its services can come up.
        // ADFA-5343 (Phase 2): under livenessLock so the poll cannot clobber this reset with a snapshot
        // whose prev it read before the reset (the two-writer race). Runs on the UI thread; the critical
        // section is a single field write, so it never blocks on the poll's probe.
        synchronized (livenessLock) { lastLiveness = null; }
        createFakeSysData(rootfsDir);
        if (serverEngine != null) serverEngine.killProcess();
        serverEngine = new PRootEngine();
        String startCmd = "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm start && tail -f /dev/null'";
        serverEngine.executeInContainer(activity, rootfsDir.getAbsolutePath(), startCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                activity.runOnUiThread(() -> host.addToLog("[Server] " + line));
                java.util.regex.Matcher m = PDSM_SVC.matcher(line);
                if (m.find()) {
                    final String svc = m.group(1);
                    activity.runOnUiThread(() -> host.onStartupProgress(svc));
                }
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
        prefs.setWatchdogEnable(true);   // sets desired=UP; the reconciler promotes WatchdogService (Phase 4b)
        host.addToLog(activity.getString(R.string.watchdog_started));
        host.startFusionPulse();
        ensuring = false;   // ADFA-5103: launch issued — release the ensure-up guard
    }

    /**
     * ADFA-4952: quiesce the environment's SERVICES (pdsm stop) so a backup/restore reads or writes a
     * STATIC rootfs (no service writing to it mid-archive). Deterministic and minimal — it does not tear
     * down the watchdog or the container; the caller brings services back with {@link #startEnvironment()}.
     * {@code onDone} runs on the main thread when pdsm stop exits (or errors — we proceed either way, the
     * point is that no service is left writing). Reuses the same pdsm command as the graceful stop.
     */
    public void stopEnvironment(Runnable onDone) {
        File rootfsDir = new File(activity.getFilesDir(), "rootfs/installed-rootfs/iiab");
        host.addToLog(activity.getString(R.string.log_server_stopping_gracefully));
        PRootEngine stopEngine = new PRootEngine();
        stopEngine.executeInContainer(activity, rootfsDir.getAbsolutePath(),
                "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm stop'",
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { activity.runOnUiThread(() -> host.addToLog("[PDSM Stop] " + line)); }
                    @Override public void onProcessExit(int exitCode) { activity.runOnUiThread(onDone); }
                    @Override public void onError(String error) { activity.runOnUiThread(onDone); }
                });
    }

    public void handleServerLaunchClick(View v) {
        // ADFA-4621 safety net: never start/stop the server during a rootfs/module install.
        if (org.iiab.controller.install.presentation.InstallProgressRepository.get().isRunning()
                || org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()
                || InstallGuard.inProgress(activity)   // ADFA-4811: durable guard survives a mid-install kill
                || org.iiab.controller.env.EnvironmentLock.ownerHeld(activity)) {   // ADFA-4957: never toggle the server while a deep-env op (backup/restore/clone) OWNS the lock. Uses ownerHeld (not isHeld) so a live content download — which runs on the server and holds no owner marker — doesn't block turn-off.
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

        if (!ServerStateRepository.get().current().alive) {
            // ADFA-4842: the actual boot is the shared, unconditional startEnvironment(). Reached now only
            // by the recovery caller (LibraryActivity:394, Phase 5).
            startEnvironment();

            // ADFA-5061: a 20 s timer used to fire a snackbar here — "Termux not opening? Enable
            // Master Watchdog to force it to gain focus." Removed, because every part of it is now
            // false. The environment is not Termux and has not been for some time; nothing has to
            // gain focus, since the server runs in a proot this process owns rather than in another
            // app; and the Master Watchdog keeps services alive while the screen is off, which has
            // no bearing on whether a start succeeds. It was advice from an era when starting the
            // server meant handing off to a second app that Oppo and Xiaomi would refuse to
            // foreground.
            //
            // It also fired on elapsed time alone, so on any slow device it told a user that
            // nothing was happening while the start was happening. The honest report of a start
            // that did not take is the timeout above, which is still here and still runs.

        }
        // ADFA-5343 (Phase 4c): the toggle no longer stops the box. A user turn-off is now an intent
        // (LibraryActivity.turnOffK2Go -> setUserWantsOn(false)) that the reconciler honors with a graceful
        // pdsm stop + proot teardown. This branch's callers (LibraryActivity install-success / recovering /
        // autostart) are all boot-gated on !alive, so the old stop branch was dead once the button
        // converted. handleServerLaunchClick is boot-only now and 4d deletes it with the autostart cluster.
    }
}
