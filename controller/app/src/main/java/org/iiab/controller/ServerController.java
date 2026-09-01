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

import androidx.appcompat.app.AppCompatActivity;

import org.iiab.controller.util.AppExecutors;

import java.io.File;

public class ServerController {

    private static final String TAG = "IIAB-ServerController";
    private static final int CHECK_INTERVAL_MS = 3000;
    // ADFA-5365: the service-downtime grace used to be declared here as well as in EnvironmentEnsure,
    // whose javadoc claimed to be "the one canonical value both actuators use" while this copy sat
    // beside it. Two constants for one threshold is how the two actuators come to disagree, so the
    // copy is gone and decide() now supplies its own defaults to every caller.

    /** Activity-side callbacks the server lifecycle needs. */
    public interface Host {
        void addToLog(String message);
        void startFusionPulse();
        void stopBtnProgress();
        void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn);
        void refreshServerUi();
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
     * <p><b>Why this exists — it must stay UNCONDITIONAL, never a start-XOR-stop toggle on the cached
     * {@code alive}.</b> (ADFA-5343 Phase 5b removed the old {@code handleServerLaunchClick} toggle; the
     * user button is now set-desired via the reconciler. This boot survives only on the STOPPED-proot
     * dashboard-rebuild hand-off in {@code SetupProgressActivity}, ADR-5343a §11.)
     * A proot MODULE install runs each runrole in its own proot with {@code --kill-on-exit}: the role does a
     * clean start → its tasks → clean stop, and when the runrole's proot exits it also kills any service it
     * (re)started. So right after a runrole finishes, the environment is DOWN — that is the intended, clean
     * per-module cycle (start → work → stop), especially when several modules install in series. Only after
     * the LAST module do we bring the environment back up, and that job belongs to the install index.
     *
     * <p>The catch: the app's cached {@link ServerStateRepository} {@code alive} can still read TRUE for a
     * moment (the 3s poll hasn't seen the runrole proot exit yet). A start-XOR-stop toggle keyed on that
     * cache would STOP on the stale TRUE instead of starting. That is exactly the bug we chased: the index
     * logged "Stopping IIAB environment gracefully" right after DONE and landed on a dead Home. The index
     * KNOWS the environment must come up now, so it starts UNCONDITIONALLY here — never a cache-keyed toggle.
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
            // ADFA-5365: a boot is judged on movement, not elapsed time. The progress fact belongs to
            // the environment, not to whichever actuator launched it, so both read the same answer;
            // with no signal (nothing launched in this process) decide() falls back to the downtime rule.
            long silentMs = org.iiab.controller.env.EnvironmentProgress.silentMs(now);
            org.iiab.controller.env.domain.EnvironmentEnsure.Action action =
                    org.iiab.controller.env.domain.EnvironmentEnsure.decide(
                            envAlive, servicesAlive, ll != null && ll.booting(),
                            servicesDownMs, silentMs);
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
        org.iiab.controller.env.EnvironmentProgress.launched();   // ADFA-5365: first sign of life
        String startCmd = "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm start && tail -f /dev/null'";
        serverEngine.executeInContainer(activity, rootfsDir.getAbsolutePath(), startCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                // ADFA-5365: whoever launched it, the environment's progress lands in one place.
                org.iiab.controller.env.EnvironmentProgress.alive();
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

    // ADFA-5343 (Phase 5b): handleServerLaunchClick — the cache-keyed start-XOR-stop toggle — is deleted.
    // Its last caller was the recovery boot (LibraryActivity's recovering branch), now routed through
    // desired (setUserWantsOn(true) + requestReconcileNow), so every server start/stop is the reconciler's.
    // The toggle's transition scaffolding went with it: the timeout Handler/Runnable and the Host's
    // getTargetServerState/setTargetServerState. The unconditional startEnvironment() above stays — it is
    // still the STOPPED-proot dashboard-rebuild hand-off boot in SetupProgressActivity (ADR-5343a §11).
}
