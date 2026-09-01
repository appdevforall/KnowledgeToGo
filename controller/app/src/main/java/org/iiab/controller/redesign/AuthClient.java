/*
 * ============================================================================
 * Name        : AuthClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5043. App-side client for the dash-node auto-login endpoint:
 *                 GET /k2go-api/auth/<service>/session  ->  { service, cookie }
 *               The server logs into Calibre-Web ("books"/"calibre") or Kolibri ("kolibri") with the
 *               stored admin credentials and returns the session cookie; the app injects it into the
 *               WebView CookieManager so the card opens already authenticated. The password never
 *               reaches the app. On any failure (service not installed/ready) the caller just opens the
 *               card without a cookie. Only reachable on-device (localhost); the box denies the API to
 *               remote clients.
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AuthClient {
    private AuthClient() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface SessionCb {
        /** {@code cookie} is a ready-to-use Cookie header ("name=value; name2=value2"). */
        void onOk(String cookie);
        /** The session couldn't be obtained (service not installed/ready, bad creds, etc.). */
        void onErr();
    }

    /**
     * Ask the box for a signed-in session cookie for a service ("books"/"calibre" or "kolibri").
     *
     * <p>ADFA-5361: {@code consumerUserAgent} is the User-Agent of the WebView that will USE the
     * session, sent as this request's own User-Agent. Calibre-Web (Flask-Login) binds a session to
     * a fingerprint of the agent, so a session minted under the box's own agent is rejected on the
     * WebView's first request — the identity is dropped, the remember_token deleted, and the card
     * opens as the anonymous Guest. It must be the WebView's string verbatim: its only job is to
     * match what the WebView will send.
     */
    public static void session(String service, String consumerUserAgent, SessionCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                String url = BoxEndpoints.API + "/auth/" + service + "/session";
                JSONObject o = new JSONObject(httpGet(url, consumerUserAgent));
                final String cookie = o.optString("cookie", "");
                if (cookie.isEmpty()) MAIN.post(cb::onErr);
                else MAIN.post(() -> cb.onOk(cookie));
            } catch (Exception e) {
                MAIN.post(cb::onErr);
            }
        });
    }

    private static String httpGet(String urlStr, String consumerUserAgent) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(4000);
            // The server does a login handshake with the local service; a missing service fails fast.
            c.setReadTimeout(12000);
            c.setRequestProperty("Accept", "application/json");
            if (consumerUserAgent != null && !consumerUserAgent.isEmpty()) {
                c.setRequestProperty("User-Agent", consumerUserAgent);
            }
            int code = c.getResponseCode();
            String text = readAll(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
            return text;
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
