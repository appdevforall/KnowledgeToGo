/*
 * ============================================================================
 * Name        : FirehoseSignalSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-386 (Layer 3). Reads the dash-node live firehose signal
 *               (GET /system/disk-guard/firehose) so the app-side backstop has
 *               a second reap trigger. Blocking; call from the guard poller
 *               thread. Never throws. See ADR-386 §6.
 * ============================================================================
 */
package org.appdevforall.k2go.diskguard.data;

import android.util.Log;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.diskguard.domain.FirehoseSignal;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Reads {@code GET /k2go-api/system/disk-guard/firehose} -> a {@link FirehoseSignal}, or {@code null}
 * when the signal cannot be read. The box being off (mid-reap, mid-restart, not installed) is the
 * ordinary reason for {@code null}, so a null is NOT "no firehose" -- the caller simply does not act.
 *
 * <p>Blocking by design; the guard poller already runs off the main thread. The response is a few
 * fields, so the byte cap is tiny.
 */
public final class FirehoseSignalSource {

    private static final String TAG = "K2Go-DiskGuard";
    private static final String URL_PATH = BoxEndpoints.API + "/system/disk-guard/firehose";
    private static final int TIMEOUT_MS = 4000;
    private static final int MAX_BYTES = 8 * 1024; // a handful of fields; refuse the absurd

    private FirehoseSignalSource() {}

    /** @return the parsed signal, or {@code null} when it could not be read (box off is the usual cause). */
    public static FirehoseSignal read() {
        try {
            String body = httpGet(URL_PATH);
            if (body.isEmpty()) return null;
            JSONObject o = new JSONObject(body);
            return new FirehoseSignal(
                    o.optBoolean("recurring", false),
                    o.optInt("maxStreak", 0),
                    o.optLong("lastTruncatedAtMs", 0L),
                    o.optLong("now", 0L));
        } catch (Exception e) {
            Log.i(TAG, "K2GO-386: firehose signal read failed: " + e.getMessage());
            return null;
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(is);
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("response over " + (MAX_BYTES / 1024) + " KB");
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }
}
