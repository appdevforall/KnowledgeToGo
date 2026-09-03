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
package org.appdevforall.k2go.redesign;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.util.AppExecutors;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AuthClient {
    private AuthClient() {}

    private static final String TAG = "K2Go-Auth";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Pause before the single retry. The failures worth retrying are the FAST ones — a refused
     * connection or a 5xx from a service that is restarting or saturated — and those are exactly the
     * ones an immediate retry asks again too soon to get a different answer, which would make the
     * retry decorative. Short enough to be invisible next to the 12 s read budget it guards.
     */
    private static final long RETRY_DELAY_MS = 800L;

    /** Thrown so a caller can tell a fast, retryable failure from the server's final word. */
    private static final class HttpStatusException extends Exception {
        final int status;
        HttpStatusException(int status, String body) {
            // Bounded: an unexpected HTML error page must not turn one log line into a screenful.
            super("HTTP " + status + (body == null || body.isEmpty() ? ""
                    : ": " + (body.length() > 200 ? body.substring(0, 200) + "…" : body)));
            this.status = status;
        }
    }

    /** {@code getMessage()} is null for several IO exceptions; the class name is better than "null". */
    private static String describe(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

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
            String url = BoxEndpoints.API + "/auth/" + service + "/session";
            try {
                final String cookie = fetchCookie(url, consumerUserAgent, service);
                if (cookie.isEmpty()) {
                    // A 200 with no cookie is the box telling us the handshake produced nothing.
                    Log.w(TAG, service + ": the box returned no session cookie");
                    MAIN.post(cb::onErr);
                } else {
                    MAIN.post(() -> cb.onOk(cookie));
                }
            } catch (Exception e) {
                // ADFA-5361: never silent. Without this line the card just opens as the anonymous
                // Guest and nothing anywhere says why — which is most of what made this bug expensive.
                Log.w(TAG, service + ": sign-in failed, the card will open unauthenticated: " + describe(e));
                MAIN.post(cb::onErr);
            }
        });
    }

    /**
     * One attempt, plus a single retry for the failures that are worth retrying.
     *
     * <p>ADFA-5361: retried are the FAST ones — a refused connection or a 5xx, which is what a
     * content service that is busy or has just been restarted answers in milliseconds while a books
     * job runs. NOT retried: a timeout (it already spent the full read budget; asking again buys the
     * same answer for twice the wait, with the sign-in overlay on screen) and a 4xx (the box's final
     * word — wrong credentials do not improve on a second ask). Same rule the box uses for its own
     * retries: {@code isTransient: status >= 500} in sockets/net-retry.ts.
     */
    // Package-private, not private: this is the branch the UI cannot reach on a healthy box (a card
    // whose service is down never opens the portal at all), so the only way to cover it is to call it
    // with a scripted server — see AuthClientRetryTest.
    static String fetchCookie(String url, String consumerUserAgent, String service) throws Exception {
        try {
            return cookieOf(httpGet(url, consumerUserAgent));
        } catch (java.net.SocketTimeoutException e) {
            throw e;                                             // already waited the full budget
        } catch (HttpStatusException e) {
            if (e.status < 500) throw e;                          // 401/403/404: final
            Log.w(TAG, service + ": " + describe(e) + " — retrying once");
        } catch (java.io.IOException e) {
            Log.w(TAG, service + ": " + describe(e) + " — retrying once");
        }
        // Runs on the IO executor, never the main thread. Restore the interrupt flag and give up
        // rather than swallowing it: an interrupted worker must not go on to open a new connection.
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
        return cookieOf(httpGet(url, consumerUserAgent));
    }

    private static String cookieOf(String json) throws Exception {
        return new JSONObject(json).optString("cookie", "");
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
            if (code < 200 || code >= 400) throw new HttpStatusException(code, text);
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
