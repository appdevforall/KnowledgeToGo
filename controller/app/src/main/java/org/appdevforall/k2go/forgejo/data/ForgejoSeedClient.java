/*
 * ============================================================================
 * Name        : ForgejoSeedClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-417 (part 2). App-side client of the dash-node Forgejo seed
 *               (static/dashboard routes.ts, K2GO-417 part 1). The box seeds Forgejo
 *               through dash-node while the server is UP: the device only POSTs to
 *               start and polls a coarse status, so a dropped app never stops the seed
 *               (the box job is detached). Distinct from the durable job engine
 *               (RestContentClient): the seed has no id/percent, only a state
 *               ("running"/"done"/"error") plus a log tail.
 *
 *               Contract:
 *                 POST /k2go-api/forgejo/seed { includeRepos }  -> 202 { state:"running" }
 *                 GET  /k2go-api/forgejo/seed/status            -> { state, lines:[...] }
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Drives the box Forgejo seed to a terminal state. {@link #drive} BLOCKS (poll loop), so callers
 * run it on an IO thread. The box job is detached, so a caller that dies mid-seed does not stop it:
 * a later {@link #drive} re-attaches by reading the same status (a running seed is left alone).
 */
public final class ForgejoSeedClient {

    /** Terminal verdict of a drive. CANCELLED only applies to a refresh the user stopped. */
    public enum Result { DONE, ERROR, CANCELLED }

    /** Streamed status-tail lines, for a log/notification. Optional (pass null to ignore). */
    public interface Listener {
        void onLine(@NonNull String line);
    }

    private static final String SEED_URL = BoxEndpoints.API + "/forgejo/seed";
    private static final String STATUS_URL = BoxEndpoints.API + "/forgejo/seed/status";
    // K2GO-422: the repo refresh reuses this same POST-then-poll shape against its own endpoints.
    private static final String REFRESH_URL = BoxEndpoints.API + "/forgejo/refresh";
    private static final String REFRESH_STATUS_URL = BoxEndpoints.API + "/forgejo/refresh/status";
    private static final String REFRESH_CANCEL_URL = BoxEndpoints.API + "/forgejo/refresh/cancel";
    private static final long POLL_MS = 2000L;
    private static final int MAX_POLL_ERRORS = 15;   // ~30s of transient blips before giving up
    // A seed (repo clones ~113 MB) can run for minutes; cap the wait so a wedged box job cannot
    // block the drive forever (the box job keeps running detached; a later drive re-attaches).
    private static final long MAX_WAIT_MS = 20 * 60 * 1000L;

    /** The last status-tail line handed to the listener, so a poll that did not advance stays quiet. */
    private String lastEmitted;
    // K2GO-422: last per-repo outcome counts reported by a refresh status (-1 = not reported / unknown).
    private int lastChanged = -1, lastProblems = -1, lastTotal = -1;

    /** Repos that advanced in the last refresh (fast-forward or merge); -1 if the box did not report it. */
    public int lastChanged() { return lastChanged; }
    /** Repos the last refresh could not update (conflict or a fetch/push failure); -1 if not reported. */
    public int lastProblems() { return lastProblems; }
    /** Repos the last refresh attempted; -1 if the box did not report it. */
    public int lastTotal() { return lastTotal; }

    /**
     * Start the seed if it is not already running, then poll to a terminal state.
     *
     * @param includeRepos seed the example repos too (admin + org are always seeded).
     * @return DONE on a seeded box, ERROR otherwise (unreachable box, seed failure, or timeout).
     */
    @NonNull
    public Result drive(boolean includeRepos, @Nullable Listener l) {
        return drive(includeRepos, false, l);
    }

    /**
     * As {@link #drive(boolean, Listener)}, but {@code force} ignores a leftover "done" status and
     * (re)starts a fresh seed. The status file is a single, shared marker, so a prior op's "done"
     * would otherwise short-circuit an INTENTIONAL re-seed (K2GO-422 post-install repos) in a few ms
     * without running anything. The box POST rewrites the status to "running" synchronously, so the
     * poll then follows the fresh run. Non-forced keeps the re-attach optimization (an app that died
     * mid-seed reads the finished box status instead of re-running the whole seed).
     */
    @NonNull
    public Result drive(boolean includeRepos, boolean force, @Nullable Listener l) {
        String state = readState(STATUS_URL, l);
        if (!force && "done".equals(state)) return Result.DONE;
        if (!"running".equals(state)) {
            // idle / error / missing (or forced past a stale done) -> (re)start it. A 409 "already
            // running" is fine: fall through to poll.
            if (!postSeed(includeRepos)) return Result.ERROR;
        }
        return poll(STATUS_URL, l);
    }

    /**
     * K2GO-422: drive a repo REFRESH to a terminal state. Same POST-then-poll shape as the seed, on the
     * refresh endpoints. Each refresh is intentional, so there is NO "done" short-circuit: unless one is
     * already running (re-attach), it POSTs a fresh run. The box refresh is non-destructive (fast-forward
     * or clean merge, skip on conflict), so re-running is safe. Returns DONE when the box refresh
     * finished, ERROR on an unreachable box or timeout.
     */
    @NonNull
    public Result refresh(@Nullable Listener l) {
        String state = readState(REFRESH_STATUS_URL, l);
        if (!"running".equals(state)) {
            if (!post(REFRESH_URL, null)) return Result.ERROR;
        }
        return poll(REFRESH_STATUS_URL, l);
    }

    /** Poll one status endpoint to a terminal state, streaming new tail lines through the listener. */
    @NonNull
    private Result poll(@NonNull String statusUrl, @Nullable Listener l) {
        final long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        int pollErrors = 0;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(POLL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return Result.ERROR; }
            String s = readState(statusUrl, l);
            if (s == null) { if (++pollErrors > MAX_POLL_ERRORS) return Result.ERROR; continue; }
            pollErrors = 0;
            if ("done".equals(s)) return Result.DONE;
            if ("error".equals(s)) return Result.ERROR;
            if ("cancelled".equals(s)) return Result.CANCELLED;   // K2GO-422: user stopped a refresh
            // "running" (or an unknown transient) -> keep polling.
        }
        return Result.ERROR;   // timed out; the box job may still finish, a later drive re-checks
    }

    /** K2GO-422: ask the box to stop a running refresh (best-effort; a poll then reads "cancelled"). */
    public void cancelRefresh() {
        post(REFRESH_CANCEL_URL, null);
    }

    /** POST /forgejo/seed with the includeRepos body; true on 2xx or 409 (already running). */
    private boolean postSeed(boolean includeRepos) {
        try {
            return post(SEED_URL, new JSONObject().put("includeRepos", includeRepos));
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    /** POST a start endpoint (optional JSON body); true if accepted (2xx) or already running (409). */
    private boolean post(@NonNull String url, @Nullable JSONObject body) {
        try {
            HttpURLConnection c = open("POST", url);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            c.disconnect();
            return (code >= 200 && code < 300) || code == 409;
        } catch (Exception e) {
            return false;
        }
    }

    /** GET a status endpoint -> the state string, streaming any new tail lines; null on a read error. */
    @Nullable
    private String readState(@NonNull String statusUrl, @Nullable Listener l) {
        try {
            HttpURLConnection c = open("GET", statusUrl);
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
            if (!ok) return null;
            JSONObject j = new JSONObject(text.isEmpty() ? "{}" : text);
            // K2GO-422: capture the refresh outcome counts when present (the seed status omits them).
            if (j.has("changed")) lastChanged = j.optInt("changed", lastChanged);
            if (j.has("problems")) lastProblems = j.optInt("problems", lastProblems);
            if (j.has("total")) lastTotal = j.optInt("total", lastTotal);
            if (l != null) {
                JSONArray lines = j.optJSONArray("lines");
                if (lines != null && lines.length() > 0) {
                    String last = lines.optString(lines.length() - 1, "");
                    if (!last.isEmpty() && !last.equals(lastEmitted)) { lastEmitted = last; l.onLine(last); }
                }
            }
            return j.optString("state", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpURLConnection open(String method, String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setUseCaches(false);
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
