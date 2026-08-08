/*
 * ============================================================================
 * Name        : InstalledChannelsSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads what the box already holds, from the in-server REST core.
 *               Blocking; call from an IO thread (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import android.util.Log;

import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.kolibri.domain.InstalledChannel;
import org.iiab.controller.kolibri.domain.InstalledLibrary;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code GET /k2go-api/kolibri/channels} — what is already on this device.
 *
 * <p>A read of the local content database, served by the box: it needs no Kolibri
 * session and does not require Kolibri itself to be running, only the dashboard
 * core. That is why it is worth asking even when the picker cannot start a download
 * — knowing what is already there is useful in its own right.
 *
 * <p>Separate from {@link KolibriRestClient} on purpose. That one owns a download
 * job: it POSTs, polls, retries and can be cancelled. This is a question with an
 * answer. Folding a query into the job client would have given the job client a
 * second life-cycle.
 *
 * <p>Blocking by design, so the caller chooses the thread. Never throws: a failure
 * is {@link InstalledLibrary#unknown()}, which callers must not read as "nothing is
 * installed".
 */
public final class InstalledChannelsSource {

    private static final String TAG = "K2Go-Kolibri";
    private static final String URL_PATH = BoxEndpoints.API + "/kolibri/channels";
    private static final int TIMEOUT_MS = 4000;

    /** A listing on a device with a large library is still small; refuse the absurd. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private InstalledChannelsSource() {
    }

    /**
     * @return what the device holds, or {@link InstalledLibrary#unknown()} when the
     *         listing could not be read — the box being off is the ordinary reason
     */
    public static InstalledLibrary read() {
        try {
            String body = httpGet(URL_PATH);
            if (body.isEmpty()) {
                return InstalledLibrary.unknown();
            }
            JSONArray arr = new JSONArray(body);
            List<InstalledChannel> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                // A row we cannot key by is dropped rather than kept with a broken
                // id: it would never match a catalog entry and would quietly make
                // the listing look larger than it is.
                InstalledChannel c = InstalledChannel.of(
                        o.optString("id", ""),
                        o.optString("name", ""),
                        o.optInt("version", 0),
                        o.optInt("filesTotal", 0),
                        o.optInt("filesAvailable", 0),
                        o.optLong("bytesTotal", 0L),
                        o.optLong("bytesAvailable", 0L));
                if (c != null) {
                    out.add(c);
                }
            }
            return InstalledLibrary.of(out);
        } catch (Exception e) {
            Log.w(TAG, "installed channels read failed: " + e.getMessage());
            return InstalledLibrary.unknown();
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
            if (code < 200 || code >= 400) {
                throw new Exception("HTTP " + code);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        try (InputStream in = is) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new Exception("listing over " + (MAX_BYTES / (1024 * 1024)) + " MB");
                }
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }
}
