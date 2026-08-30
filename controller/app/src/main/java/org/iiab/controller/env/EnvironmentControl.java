/*
 * ============================================================================
 * Name        : EnvironmentControl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4957. Context-based quiescing of the Debian environment SERVICES (pdsm stop),
 *               extracted from ServerController so a foreground service (DeepOpService) can stop the
 *               environment off ANY Activity — the same deterministic pdsm command the UI path uses.
 *               stop() runs `pdsm stop`; callbacks fire on the PRootEngine worker thread.
 *               ADFA-5343 (Phase 4): start() is now here too — the OFF-UI boot the app-scoped reconciler
 *               owner uses to bring the box up with no foreground Activity (the process-scoped owner the
 *               ADR §1.1 root cause needs). Both share createFakeSysData below, one copy of the fake /proc
 *               data. ServerController.doLaunchEnvironment keeps its own Activity-UI boot until the
 *               app-scoped actuator is device-verified (then it delegates here / is deleted).
 * ============================================================================
 */
package org.iiab.controller.env;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.iiab.controller.PRootEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

public final class EnvironmentControl {

    private static final String TAG = "IIAB-EnvControl";
    private static final String PATH_ENV =
            "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc ";
    private static final String CMD_STOP = PATH_ENV + "'/usr/local/bin/pdsm stop'";
    // ADFA-5343 (Phase 4): the persistent boot — the same command ServerController.doLaunchEnvironment
    // uses (pdsm start, then `tail -f /dev/null` so the proot outlives the command and stays up).
    private static final String CMD_START = PATH_ENV + "'/usr/local/bin/pdsm start && tail -f /dev/null'";

    private EnvironmentControl() {}

    /** The installed rootfs services directory the pdsm commands run in. */
    public static File rootfs(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
    }

    /** Optional log sink for the pdsm output lines. */
    public interface LineSink { void onLine(String line); }

    /**
     * Quiesce the environment's services (pdsm stop) so a backup/restore reads or writes a STATIC
     * rootfs. {@code onDone} runs when pdsm stop exits (or errors — we proceed either way, the point is
     * that no service is left writing). Runs on the PRootEngine worker thread.
     */
    public static void stop(Context ctx, final LineSink log, final Runnable onDone) {
        new PRootEngine().executeInContainer(ctx.getApplicationContext(), rootfs(ctx).getAbsolutePath(), CMD_STOP,
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { if (log != null) log.onLine("[PDSM Stop] " + line); }
                    @Override public void onProcessExit(int exitCode) { if (onDone != null) onDone.run(); }
                    @Override public void onError(String error) { if (onDone != null) onDone.run(); }
                });
    }

    /**
     * ADFA-5343 (Phase 4): the OFF-UI boot — bring the environment up in a fresh, app-scoped
     * {@link PRootEngine} with no Activity, so the app-scoped reconciler owner can boot the box when no
     * screen is foregrounded. Mirrors {@code ServerController.doLaunchEnvironment}'s launch
     * (createFakeSysData + {@code pdsm start && tail -f /dev/null}) minus the Activity-UI callbacks
     * (onStartupBegan / per-service progress / fusion pulse) — those belong to a foreground screen, and
     * the UI observes the published phase instead.
     *
     * <p>The caller (the reconciler) HOLDS the returned engine — it is the process-scoped owner of the
     * launch, which is the ADR §1.1 root-cause fix (the proot outlives any Activity). Runs the command on
     * the PRootEngine worker thread; call after an idempotency decision (EnvironmentEnsure) so a live
     * proot is never stacked.
     */
    public static PRootEngine start(Context ctx, final LineSink log) {
        File rootfsDir = rootfs(ctx);
        createFakeSysData(rootfsDir);
        PRootEngine engine = new PRootEngine();
        engine.executeInContainer(ctx.getApplicationContext(), rootfsDir.getAbsolutePath(), CMD_START,
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { if (log != null) log.onLine("[Server] " + line); }
                    @Override public void onProcessExit(int exitCode) { if (log != null) log.onLine("[Server] engine exit " + exitCode); }
                    @Override public void onError(String error) { if (log != null) log.onLine("[Server] error " + error); }
                });
        return engine;
    }

    /**
     * Writes the fake /proc files (uptime/version/stat/loadavg) the container expects. Extracted
     * verbatim from ServerController.createFakeSysData; that method now delegates here so there is a
     * single implementation.
     */
    public static void createFakeSysData(File rootfsDir) {
        try {
            File procDir = new File(rootfsDir, "proc");
            if (!procDir.exists()) procDir.mkdirs();

            long uptimeMillis = SystemClock.elapsedRealtime();
            long bootTimeSeconds = (System.currentTimeMillis() - uptimeMillis) / 1000;
            double uptimeSeconds = uptimeMillis / 1000.0;

            File uptimeFile = new File(procDir, ".uptime");
            if (uptimeFile.exists()) uptimeFile.delete();
            FileOutputStream fosUp = new FileOutputStream(uptimeFile);
            fosUp.write(String.format(Locale.US, "%.2f %.2f\n", uptimeSeconds, uptimeSeconds).getBytes());
            fosUp.close();

            File versionFile = new File(procDir, ".version");
            if (!versionFile.exists()) {
                FileOutputStream fosVer = new FileOutputStream(versionFile);
                fosVer.write("Linux version 6.17.0-PRoot-IIAB (builder@iiab) (Android NDK) #1 SMP PREEMPT Thu Apr 30 20:00:00 UTC 2026\n".getBytes());
                fosVer.close();
            }

            File statFile = new File(procDir, ".stat");
            if (statFile.exists()) statFile.delete();
            FileOutputStream fosStat = new FileOutputStream(statFile);
            String statContent = "cpu  1000 0 1000 10000 0 0 0 0 0 0\n" +
                    "btime " + bootTimeSeconds + "\n";
            fosStat.write(statContent.getBytes());
            fosStat.close();

            File loadavgFile = new File(procDir, ".loadavg");
            if (!loadavgFile.exists()) {
                FileOutputStream fosLoad = new FileOutputStream(loadavgFile);
                fosLoad.write("0.00 0.00 0.00 1/1 1\n".getBytes());
                fosLoad.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create dynamic fake sysdata", e);
        }
    }
}
