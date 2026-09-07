/*
 * ============================================================================
 * Name        : MapsDownloadRpc.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. Drives the in-proot maps aria2c over loopback JSON-RPC:
 *               live progress, pause/resume, and shutdown-on-complete so the
 *               blocking Ansible download task returns. Degrades to idle when the
 *               RPC is absent (a stock rootfs without the is_proot patch).
 * ============================================================================
 */
package org.appdevforall.k2go.maps.data;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.appdevforall.k2go.maps.domain.MapsDownloadProgress;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The app-side driver for the maps download's in-proot aria2c (K2GO-394).
 *
 * <p>proot shares the host network namespace, so the aria2c the is_proot download task opens on
 * {@code 127.0.0.1:<port>} is reachable here. This polls {@code aria2.tellActive} ~1 s for the
 * subordinate download bar, exposes pause / resume, and -- the load-bearing bit verified on device --
 * calls {@code aria2.shutdown} the moment the download completes, because aria2c with {@code
 * --enable-rpc} does NOT exit on its own and the Ansible {@code shell} task would otherwise block
 * forever.
 *
 * <p><b>Per file, and tolerant of absence.</b> Each downloaded file is a fresh aria2c on the same
 * port; between files, and on a stock rootfs that has no is_proot patch, the RPC simply does not
 * answer. That is not an error -- it reports {@link Listener#onDownloadIdle()} and keeps polling, so
 * the caller falls back to phase-only progress (Variant 3) with no crash.
 *
 * <p>Shutdown fires only on a genuine {@code complete}: an errored or paused download is never shut
 * down (that would let the shell task move a partial file), so a give-up or a hang is left to the
 * stall watch / recovery. Cancellation is the operation-level kill (it takes proot and aria2c with
 * it), not an RPC call here.
 */
public final class MapsDownloadRpc {

    private static final String TAG = "K2Go-MapsRpc";
    private static final long POLL_MS = 1000L;
    private static final int TIMEOUT_MS = 3000;

    /** What the caller (InstallService) bridges to the UI repository and the reconnection manager. */
    public interface Listener {
        /** A live download exists (active or paused): render the subordinate bar. Main thread. */
        void onProgress(MapsDownloadProgress progress);

        /** No download to show right now -- RPC absent, between files, or finished. Main thread. */
        void onDownloadIdle();
    }

    private final String endpoint;
    private final String token;         // "token:<secret>", or null when no secret was set
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler poll;               // the background poll looper
    private volatile boolean running;
    private boolean sawActive;          // seen an active download since the RPC last came up
    private boolean rpcUpLastPoll;      // to detect a fresh aria2c (a new per-file session)

    public MapsDownloadRpc(int port, String secret, Listener listener) {
        this.endpoint = "http://127.0.0.1:" + port + "/jsonrpc";
        this.token = (secret != null && !secret.isEmpty()) ? "token:" + secret : null;
        this.listener = listener;
    }

    /** Begin polling on a background looper. Idempotent. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        sawActive = false;
        rpcUpLastPoll = false;
        thread = new HandlerThread("maps-download-rpc");
        thread.start();
        poll = new Handler(thread.getLooper());
        poll.post(this::tick);
    }

    /** Stop polling and drop the looper. Does NOT shut aria2c down -- teardown/cancel own that. */
    public void stop() {
        running = false;
        if (poll != null) {
            poll.removeCallbacksAndMessages(null);
            poll = null;   // so callAsync's null guard means "stopped", not "posting to a dead looper"
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    /** Pause the active download (best-effort). Safe to call when nothing is downloading. */
    public void pause() {
        callAsync("aria2.pauseAll");
    }

    /** Resume a paused download (best-effort). */
    public void resume() {
        callAsync("aria2.unpauseAll");
    }

    // ---- the poll loop ---------------------------------------------------------------------------

    private void tick() {
        if (!running) {
            return;
        }
        JSONObject stat = rpc("aria2.getGlobalStat", null);
        if (stat == null) {
            // RPC not answering: no aria2c up (between files, done, or a stock rootfs). Not an error.
            rpcUpLastPoll = false;
            postIdle();
            rearm();
            return;
        }
        if (!rpcUpLastPoll) {
            sawActive = false;   // a fresh aria2c since the last poll -- start this file's session clean
            rpcUpLastPoll = true;
        }
        int active = optInt(stat, "numActive");
        int waiting = optInt(stat, "numWaiting");
        int stopped = optInt(stat, "numStopped");
        if (active > 0) {
            sawActive = true;
            postProgress(readOne("aria2.tellActive", true));
        } else if (waiting > 0) {
            // A paused (or queued) download: show it, never shut it down.
            postProgress(readOne("aria2.tellWaiting", false));
        } else if (sawActive && stopped > 0 && !anyStoppedError()) {
            // Was downloading, nothing active/waiting, and something stopped without error -> the file
            // finished. Shut aria2c down so the blocking shell task returns. Gate on getGlobalStat's
            // numStopped, not on tellStopped being non-empty, so a momentarily empty tellStopped does
            // not miss the shutdown and leave the task hanging until the stall watch.
            Log.i(TAG, "download complete; shutting aria2c down so the runrole task advances");
            call("aria2.shutdown", null);
            postProgress(MapsDownloadProgress.of("complete", 0, 0, 0));
            sawActive = false;
        } else {
            // Nothing to show: still resolving the metalink, or a stop that errored (leave that to the
            // stall watch / recovery -- shutting down would move a partial file as if complete).
            postIdle();
        }
        rearm();
    }

    private void rearm() {
        if (running && poll != null) {
            poll.postDelayed(this::tick, POLL_MS);
        }
    }

    /** Read the single active/waiting download's status+bytes into a progress snapshot. */
    private MapsDownloadProgress readOne(String method, boolean active) {
        JSONObject params = null;
        JSONArray arr = rpcArray(method, active
                ? new Object[]{keysParam()}
                : new Object[]{0, 1, keysParam()});
        if (arr == null || arr.length() == 0) {
            return MapsDownloadProgress.none();
        }
        JSONObject d = arr.optJSONObject(0);
        if (d == null) {
            return MapsDownloadProgress.none();
        }
        return MapsDownloadProgress.of(
                d.optString("status", ""),
                optLong(d, "completedLength"),
                optLong(d, "totalLength"),
                optLong(d, "downloadSpeed"));
    }

    /** Whether any recent stopped download errored -- the shutdown guard (never shut down on an error,
     *  that would move a partial file). An empty/unreadable list is "no error": the numStopped gate in
     *  {@link #tick} already established that something stopped. */
    private boolean anyStoppedError() {
        JSONArray arr = rpcArray("aria2.tellStopped", new Object[]{0, 5, keysParam()});
        if (arr == null) {
            return false;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d != null && "error".equals(d.optString("status"))) {
                return true;
            }
        }
        return false;
    }

    private JSONArray keysParam() {
        JSONArray keys = new JSONArray();
        keys.put("gid");
        keys.put("status");
        keys.put("completedLength");
        keys.put("totalLength");
        keys.put("downloadSpeed");
        return keys;
    }

    // ---- JSON-RPC over HTTP ----------------------------------------------------------------------

    private void callAsync(final String method) {
        if (poll != null) {
            poll.post(() -> call(method, null));
        }
    }

    /** Fire a method for its side effect; ignore the result. */
    private void call(String method, Object[] extraParams) {
        rpc(method, extraParams);
    }

    /** Call a method whose result is a JSON object (getGlobalStat). Null on any failure. */
    private JSONObject rpc(String method, Object[] extraParams) {
        JSONObject resp = post(method, extraParams);
        return resp == null ? null : resp.optJSONObject("result");
    }

    /** Call a method whose result is a JSON array (tellActive/Waiting/Stopped). Null on any failure. */
    private JSONArray rpcArray(String method, Object[] extraParams) {
        JSONObject resp = post(method, extraParams);
        return resp == null ? null : resp.optJSONArray("result");
    }

    private JSONObject post(String method, Object[] extraParams) {
        HttpURLConnection c = null;
        try {
            JSONArray params = new JSONArray();
            if (token != null) {
                params.put(token);
            }
            if (extraParams != null) {
                for (Object p : extraParams) {
                    params.put(p);
                }
            }
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("id", "k2go");
            body.put("method", method);
            body.put("params", params);

            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            if (c.getResponseCode() != 200) {
                return null;
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int r;
            while ((r = c.getInputStream().read(chunk)) != -1) {
                buf.write(chunk, 0, r);
            }
            return new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;   // unreachable / between files / stock rootfs -- caller treats as idle
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void postProgress(final MapsDownloadProgress p) {
        main.post(() -> listener.onProgress(p));
    }

    private void postIdle() {
        main.post(listener::onDownloadIdle);
    }

    private static int optInt(JSONObject o, String key) {
        try {
            return Integer.parseInt(o.optString(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long optLong(JSONObject o, String key) {
        try {
            return Long.parseLong(o.optString(key, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
