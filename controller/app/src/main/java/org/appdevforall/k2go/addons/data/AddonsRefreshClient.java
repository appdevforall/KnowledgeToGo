/*
 * ============================================================================
 * Name        : AddonsRefreshClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-99. App-side client of the dash-node add-ons refresh
 *               (static/dashboard routes.ts). The box re-mirrors the Code on the
 *               Go add-ons gallery while the server is UP: the device only POSTs
 *               to start and polls a coarse status, so a dropped app never stops
 *               the refresh (the box job is detached). Same POST-then-poll shape
 *               as ForgejoSeedClient.refresh(); the status carries downloaded and
 *               failed counts instead of per-repo outcomes.
 *
 *               Contract:
 *                 POST /k2go-api/addons/refresh         -> 202 { state:"running" }
 *                 GET  /k2go-api/addons/refresh/status   -> { state, lines, downloaded, reused, failed, upToDate }
 *                 POST /k2go-api/addons/refresh/cancel    -> { state:"cancelled" }
 * ============================================================================
 */
package org.appdevforall.k2go.addons.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Drives the box add-ons refresh to a terminal state. {@link #refresh} BLOCKS (poll loop), so callers
 * run it on an IO thread. The box job is detached, so a caller that dies mid-run does not stop it: a
 * later {@link #refresh} re-attaches by reading the same status (a running refresh is left alone).
 */
public final class AddonsRefreshClient {

    /** Terminal verdict of a refresh. */
    public enum Result { DONE, ERROR, CANCELLED }

    /** Streamed status-tail lines, for a live one-line view. Optional (pass null to ignore). */
    public interface Listener {
        void onLine(@NonNull String line);
    }

    private static final String REFRESH_URL = BoxEndpoints.API + "/addons/refresh";
    private static final String REFRESH_STATUS_URL = BoxEndpoints.API + "/addons/refresh/status";
    private static final String REFRESH_CANCEL_URL = BoxEndpoints.API + "/addons/refresh/cancel";
    private static final long POLL_MS = 2000L;
    private static final int MAX_POLL_ERRORS = 15;   // ~30s of transient blips before giving up
    // The mirror pulls the full set of .cgp files (hundreds of MB), so the run can take many minutes;
    // cap the wait so a wedged box job cannot block forever (the box job keeps running detached).
    private static final long MAX_WAIT_MS = 30 * 60 * 1000L;

    /** The last status-tail line handed to the listener, so a poll that did not advance stays quiet. */
    private String lastEmitted;
    private int lastDownloaded = -1, lastReused = -1, lastFailed = -1;
    private boolean lastUpToDate = false;

    /** Files downloaded in the last refresh; -1 if the box did not report it. */
    public int lastDownloaded() { return lastDownloaded; }
    /** Files reused unchanged in the last refresh; -1 if the box did not report it (K2GO-441). */
    public int lastReused() { return lastReused; }
    /** Files the last refresh could not fetch or verify; -1 if the box did not report it. */
    public int lastFailed() { return lastFailed; }
    /** K2GO-441: true when the published set was unchanged, so the refresh downloaded nothing. */
    public boolean lastUpToDate() { return lastUpToDate; }

    /**
     * Start the refresh if it is not already running, then poll to a terminal state. Each refresh is
     * intentional, so there is no "done" short-circuit: unless one is already running (re-attach), it
     * POSTs a fresh run. The box refresh is safe to re-run (it mirrors into a staging dir and only
     * swaps the live gallery on success). Returns DONE when the box refresh finished, ERROR on an
     * unreachable box or timeout, CANCELLED if the user stopped it.
     */
    @NonNull
    public Result refresh(@Nullable Listener l) {
        String state = readState(l);
        if (!"running".equals(state)) {
            if (!post(REFRESH_URL)) return Result.ERROR;
        }
        return poll(l);
    }

    /** Ask the box to stop a running refresh (best-effort; a poll then reads "cancelled"). */
    public void cancel() {
        post(REFRESH_CANCEL_URL);
    }

    @NonNull
    private Result poll(@Nullable Listener l) {
        final long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        int pollErrors = 0;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(POLL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return Result.ERROR; }
            String s = readState(l);
            if (s == null) { if (++pollErrors > MAX_POLL_ERRORS) return Result.ERROR; continue; }
            pollErrors = 0;
            if ("done".equals(s)) return Result.DONE;
            if ("error".equals(s)) return Result.ERROR;
            if ("cancelled".equals(s)) return Result.CANCELLED;
            // "running" (or an unknown transient) -> keep polling.
        }
        return Result.ERROR;   // timed out; the box job may still finish, a later refresh re-checks
    }

    /** POST a start/cancel endpoint; true if accepted (2xx) or already running (409). */
    private boolean post(@NonNull String url) {
        try {
            HttpURLConnection c = open("POST", url);
            int code = c.getResponseCode();
            c.disconnect();
            return (code >= 200 && code < 300) || code == 409;
        } catch (Exception e) {
            return false;
        }
    }

    /** GET the status endpoint -> the state string, streaming any new tail line; null on a read error. */
    @Nullable
    private String readState(@Nullable Listener l) {
        try {
            HttpURLConnection c = open("GET", REFRESH_STATUS_URL);
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
            if (!ok) return null;
            JSONObject j = new JSONObject(text.isEmpty() ? "{}" : text);
            if (j.has("downloaded")) lastDownloaded = j.optInt("downloaded", lastDownloaded);
            if (j.has("reused")) lastReused = j.optInt("reused", lastReused);
            if (j.has("failed")) lastFailed = j.optInt("failed", lastFailed);
            if (j.has("upToDate")) lastUpToDate = j.optBoolean("upToDate", lastUpToDate);
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
