/*
 * ============================================================================
 * Name        : BoxJson.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: one JSON call to a box (this phone's box or another one).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** The HTTP call of the trust adapters (HttpRingBox, HttpClassHello, HttpVouchBox). */
final class BoxJson {

    private static final int MAX_ANSWER = 16 * 1024;

    private BoxJson() {}

    static String str(JSONObject o, String name) {
        Object x = o.opt(name);
        return x instanceof String ? (String) x : null;
    }

    static final class Answer {
        final int status;
        final JSONObject json;

        Answer(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }
    }

    static Answer call(String method, String url, JSONObject body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod(method);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            JSONObject json = null;
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = s.read(buf)) > 0 && out.size() <= MAX_ANSWER) out.write(buf, 0, n);
                    String text = out.toString("UTF-8").trim();
                    if (text.startsWith("{") && out.size() <= MAX_ANSWER) json = new JSONObject(text);
                }
            }
            return new Answer(status, json);
        } catch (Exception e) {
            return new Answer(-1, null);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
