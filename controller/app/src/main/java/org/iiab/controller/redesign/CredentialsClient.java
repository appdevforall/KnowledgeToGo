/*
 * ============================================================================
 * Name        : CredentialsClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5044. App-side client for the dash-node service-credential store:
 *                 GET    /k2go-api/credentials/<service>  -> { username, isDefault, origin }
 *                 POST   /k2go-api/credentials/<service>  { username, password } -> { ok, verified }
 *                 DELETE /k2go-api/credentials/<service>  -> reset to the box default
 *               Drives the Settings -> Authentication screen so the user can keep the admin sign-ins
 *               (Calibre-Web / Kolibri) that auto-login (ADFA-5043) relies on correct. The stored
 *               password is never returned by the box (describe omits it); this only sets/reads the
 *               username + default/custom state. Localhost-only; the box denies the API to remote clients.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Handler;
import android.os.Looper;

import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;
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
    /** {@code verified} = the box confirmed the credentials against the live service (Kolibri today). */
    public interface SaveCb { void onOk(boolean verified); void onErr(int status); }
    public interface ResetCb { void onOk(String username, boolean isDefault); void onErr(); }

    private static String url(String service) { return BoxEndpoints.API + "/credentials/" + service; }

    /** Current stored username + whether it's still the box default (no password is ever returned). */
    public static void describe(String service, DescribeCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject o = new JSONObject(request("GET", url(service), null));
                final String user = o.optString("username", "");
                final String pass = o.optString("password", "");   // present only while default
                final boolean isDefault = o.optBoolean("isDefault", false);
                MAIN.post(() -> cb.onOk(user, pass, isDefault));
            } catch (Exception e) {
                MAIN.post(cb::onErr);
            }
        });
    }

    /** Save new credentials. The box verifies live where it can (Kolibri) and saves either way. */
    public static void save(String service, String username, String password, SaveCb cb) {
        AppExecutors.get().io().execute(() -> {
            int[] status = {0};
            try {
                JSONObject body = new JSONObject().put("username", username).put("password", password);
                JSONObject o = new JSONObject(request("POST", url(service), body, status));
                final boolean verified = o.optBoolean("verified", false);
                MAIN.post(() -> cb.onOk(verified));
            } catch (Exception e) {
                final int s = status[0];
                MAIN.post(() -> cb.onErr(s));
            }
        });
    }

    /** Reset the service back to the box default (or the env override, if set). */
    public static void reset(String service, ResetCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject o = new JSONObject(request("DELETE", url(service), null));
                final String user = o.optString("username", "");
                final boolean isDefault = o.optBoolean("isDefault", false);
                MAIN.post(() -> cb.onOk(user, isDefault));
            } catch (Exception e) {
                MAIN.post(cb::onErr);
            }
        });
    }

    private static String request(String method, String urlStr, JSONObject body) throws Exception {
        return request(method, urlStr, body, new int[1]);
    }

    /** {@code statusOut[0]} receives the HTTP status so callers can map 401/403/… to a message. */
    private static String request(String method, String urlStr, JSONObject body, int[] statusOut) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);   // POST may verify against the live service (Kolibri login)
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int code = c.getResponseCode();
            statusOut[0] = code;
            String text = readAll(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
            return text.isEmpty() ? "{}" : text;
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
