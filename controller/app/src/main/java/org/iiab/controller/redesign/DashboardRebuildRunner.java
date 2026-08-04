/*
 * ============================================================================
 * Name        : DashboardRebuildRunner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5011. App-orchestrated rebuild of the dash-node REST core (Track A of ADR-5011).
 *               The app is the surgeon and the box is the asleep patient: because
 *               PRootEngine#executeInContainer launches a proot, we own the rootfs exclusively, so —
 *               like a module runrole — we STOP the box services first, then work, and leave the box
 *               stopped for the INDEX to boot persistently afterwards (see "Who starts the box" below).
 *
 *               Bootstrap without shipping code in the APK: the newest scripts are pulled straight
 *               from the on-device clone's remote via `git fetch` + `git show origin/<branch>:tools/…`
 *               into a temp dir (no working-tree change, uses the clone's existing auth — works on a
 *               private repo). We then RUN them from temp. This works even from an old rootfs whose
 *               clone predates the scripts, with nothing bundled.
 *
 *                 pdsm stop
 *                 -> git fetch + extract preflight/rebuild/smoke to /tmp/k2go + run preflight
 *                 -> (only if preflight OK) run rebuild-dashboard.sh (git reset --hard -> build ->
 *                    staged smoke test -> back up live dist -> atomic swap -> verify -> rollback)
 *                 -> leave the box STOPPED (the INDEX boots it persistently — see below)
 *
 *               Who starts the box back up: NOT this runner. Every proot here is transient with
 *               --kill-on-exit, so a service-side `pdsm start` would start services and then kill them
 *               the instant the proot exits (the "dead Home" bug). Instead, exactly like a proot MODULE
 *               install, the install INDEX (SetupProgressActivity) is the actuator: after we finish it
 *               calls ServerController.startEnvironment() ('pdsm start && tail -f /dev/null'), a PERSISTENT
 *               process-scoped proot that keeps the services alive, then waits for the REST core to answer
 *               before redirecting. So we own the rootfs (stop) for the rebuild and hand the boot back to
 *               the index.
 *
 *               Preflight is non-destructive and gates the rest: it refuses on a dirty clone (so a
 *               user's local edits are never discarded by the reset), no internet, or low disk. The
 *               live dashboard is backed up inside rebuild-dashboard.sh; a bad build never ships.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;

import androidx.annotation.NonNull;

import org.iiab.controller.PRootEngine;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DashboardRebuildRunner {

    /** PATH-normalized login shell, matching how InstallService invokes commands in the container. */
    private static final String SHELL =
            "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc";
    private static final String CLONE = "/opt/iiab-android";
    private static final String BRANCH = "feat/ADFA-5011-dashboard-rebuild";        // TODO(ADFA-5011): override for pre-merge testing
    private static final String TMP = "/tmp/k2go";       // where we drop the newest scripts to run them

    public interface Callback {
        void onLog(String line);
        /** Preflight finished; {@code r.ok} says whether it is safe to proceed. The rebuild only runs
         *  if the preflight passed. Reported so the UI can show installed/available + reasons. */
        void onPreflight(PreflightResult r);
        void onDone();
        void onError(String reason);
    }

    private final Context ctx;
    private final PRootEngine engine;
    private final String rootfsDir;

    public DashboardRebuildRunner(@NonNull Context ctx, @NonNull PRootEngine engine, @NonNull String rootfsDir) {
        this.ctx = ctx.getApplicationContext();
        this.engine = engine;
        this.rootfsDir = rootfsDir;
    }

    /** Run the full sequence. Callbacks arrive on the PRootEngine worker thread; marshal to the UI. */
    public void start(@NonNull Callback cb) {
        cb.onLog("[rebuild] stopping services (exclusive rootfs)…");
        stopServices(() -> bootstrapAndPreflight(cb));
    }

    /** Fetch the newest scripts from the clone's remote into {@code /tmp/k2go} (no working-tree
     *  change) and run the preflight from there. Nothing destructive happens here. */
    private void bootstrapAndPreflight(Callback cb) {
        cb.onLog("[rebuild] fetching latest tools + preflight…");
        final String show = "git show origin/" + BRANCH + ":tools/";
        final String cmd =
                "mkdir -p " + TMP + " && cd " + CLONE
                        + " && git fetch origin " + BRANCH
                        + " && " + show + "preflight-dashboard.sh > " + TMP + "/preflight.sh"
                        + " && " + show + "rebuild-dashboard.sh > " + TMP + "/rebuild.sh"
                        + " && " + show + "dashboard-smoketest.sh > " + TMP + "/smoketest.sh"
                        + " && sh " + TMP + "/preflight.sh";
        final StringBuilder out = new StringBuilder();
        engine.executeInContainer(ctx, rootfsDir, SHELL + " '" + cmd + "'",
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { out.append(line).append('\n'); cb.onLog(line); }
                    @Override public void onProcessExit(int exitCode) {
                        PreflightResult r = PreflightResult.parse(out.toString());
                        cb.onPreflight(r);
                        if (r.ok && exitCode == 0) {
                            runRebuild(cb);
                        } else {
                            // Nothing was touched. Leave the box stopped — the INDEX brings the
                            // environment back up persistently (see class note); just report why.
                            cb.onError(r.reasonSummary());
                        }
                    }
                    @Override public void onError(String error) { cb.onError(error); }
                });
    }

    private void runRebuild(Callback cb) {
        cb.onLog("[rebuild] building + testing + swapping…");
        // Run the temp copy; point it at the temp smoke test so it doesn't need the clone's copy.
        final String cmd = "cd " + CLONE + " && K2GO_SMOKE=" + TMP + "/smoketest.sh"
                + " K2GO_BRANCH=" + BRANCH + " sh " + TMP + "/rebuild.sh";
        engine.executeInContainer(ctx, rootfsDir, SHELL + " '" + cmd + "'",
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { cb.onLog(line); }
                    @Override public void onProcessExit(int exitCode) {
                        // Leave the box STOPPED. rebuild.sh's pdsm restarts run in a transient proot and
                        // die with --kill-on-exit, so a service-side start would only start-then-kill. The
                        // INDEX is the actuator that boots the environment persistently after we finish
                        // (startEnvironment: 'pdsm start && tail -f /dev/null'), exactly like the module flow.
                        if (exitCode == 0) cb.onDone(); else cb.onError("rebuild failed");
                    }
                    @Override public void onError(String error) { cb.onError(error); }
                });
    }

    private void stopServices(Runnable then) { pdsm("stop", then); }

    private void pdsm(String action, Runnable then) {
        engine.executeInContainer(ctx, rootfsDir, SHELL + " '/usr/local/bin/pdsm " + action + "'",
                new PRootEngine.OutputListener() {
                    @Override public void onOutputLine(String line) { /* noise */ }
                    @Override public void onProcessExit(int exitCode) { then.run(); }
                    @Override public void onError(String error) { then.run(); }   // best-effort; never wedge
                });
    }

    /**
     * Pure parse of the preflight's machine-readable line. No Android deps → unit-testable. Reads the
     * LAST {@code PREFLIGHT_RESULT={json}} line in the output. If none/invalid, treats it as not-OK.
     */
    public static final class PreflightResult {
        public final boolean ok;
        public final String installed;
        public final String available;
        public final boolean updateAvailable;
        public final List<String> reasons;

        public PreflightResult(boolean ok, String installed, String available,
                               boolean updateAvailable, List<String> reasons) {
            this.ok = ok;
            this.installed = installed;
            this.available = available;
            this.updateAvailable = updateAvailable;
            this.reasons = reasons;
        }

        public String reasonSummary() {
            if (reasons == null || reasons.isEmpty()) return "preflight failed";
            StringBuilder sb = new StringBuilder();   // String.join is API 26+; minSdk is 24
            for (int i = 0; i < reasons.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(reasons.get(i));
            }
            return sb.toString();
        }

        public static PreflightResult parse(String output) {
            String json = null;
            if (output != null) {
                for (String line : output.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("PREFLIGHT_RESULT=")) json = t.substring("PREFLIGHT_RESULT=".length()).trim();
                }
            }
            if (json == null) return new PreflightResult(false, "unknown", "unknown", false,
                    listOf("no_preflight_output"));
            try {
                JSONObject o = new JSONObject(json);
                List<String> reasons = new ArrayList<>();
                JSONArray ra = o.optJSONArray("reasons");
                if (ra != null) for (int i = 0; i < ra.length(); i++) reasons.add(ra.optString(i, ""));
                return new PreflightResult(
                        o.optBoolean("ok", false),
                        o.optString("installed", "unknown"),
                        o.optString("available", "unknown"),
                        o.optBoolean("update_available", false),
                        reasons);
            } catch (Exception e) {
                return new PreflightResult(false, "unknown", "unknown", false, listOf("bad_preflight_json"));
            }
        }

        private static List<String> listOf(String s) { List<String> l = new ArrayList<>(); l.add(s); return l; }
    }
}
