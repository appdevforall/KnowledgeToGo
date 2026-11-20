/*
 * ============================================================================
 * Name        : CredentialsClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5044, K2GO-552. App-side client for the dash-node service-credential store:
 *                 GET  /k2go-api/credentials/<service>           -> { username, isDefault, origin }
 *                 POST /k2go-api/credentials/<service>           { username, password } -> { ok, verified }
 *                 GET  /k2go-api/credentials/<service>/status    -> { state, factoryDefault }
 *                 POST /k2go-api/credentials/<service>/password  { password } -> { ok, verified }
 *                 POST /k2go-api/credentials/<service>/reset     -> { ok, verified }
 *               The stored password is never returned by the box, except the public factory one.
 *               Localhost-only; the box denies the API to remote clients.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.data;

import android.os.Handler;
import android.os.Looper;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.util.AppExecutors;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class CredentialsClient {
    private CredentialsClient() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** {@code password} is only non-empty when the service is still at the factory default (the box
     *  returns the public default so the form can prefill it); a custom password is never returned. */
    public interface DescribeCb { void onOk(String username, String password, boolean isDefault); void onErr(); }

    /** A non-2xx answer. {@code code} is the box's error code ("weak_password", "mismatch", ...). */
    static final class HttpError extends Exception {
        final int status;
        final String code;

        HttpError(int status, String code) {
            super("HTTP " + status + (code.isEmpty() ? "" : " " + code));
            this.status = status;
            this.code = code;
        }
    }

    static String url(String service) { return BoxEndpoints.API + "/credentials/" + service; }

    /** Current stored username + whether it's still the box default (no password is ever returned). */
    public static void describe(String service, DescribeCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject o = request("GET", url(service), null);
                final String user = o.optString("username", "");
                final String pass = o.optString("password", "");   // present only while default
                final boolean isDefault = o.optBoolean("isDefault", false);
                MAIN.post(() -> cb.onOk(user, pass, isDefault));
            } catch (Exception e) {
                MAIN.post(cb::onErr);
            }
        });
    }

    /** Blocking; call it off the main thread. Throws {@link HttpError} on a non-2xx answer. */
    static JSONObject request(String method, String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(4000);
            // A change runs the service's own admin tool (Kolibri and Calibre-Web take several seconds
            // on a phone), then a sign-in check.
            c.setReadTimeout(150_000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int status = c.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) {
                String code = "";
                try { code = new JSONObject(text).optString("error", ""); } catch (Exception ignore) { }
                throw new HttpError(status, code);
            }
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } finally {
            c.disconnect();
        }
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
