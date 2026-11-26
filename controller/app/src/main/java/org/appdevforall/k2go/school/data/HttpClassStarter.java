/*
 * ============================================================================
 * Name        : HttpClassStarter.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: opens a class on this phone's box (POST /school/teacher/start).
 * ============================================================================
 */
package org.appdevforall.k2go.school.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.domain.ClassStarter;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** The teacher routes answer only localhost: this is this phone's box, never another one. */
public final class HttpClassStarter implements ClassStarter {

    private final String url;

    public HttpClassStarter() { this(BoxEndpoints.BASE + "/school/teacher/start"); }

    HttpClassStarter(String url) { this.url = url; }   // tests

    @Override
    public Result start(String name) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("name", name == null || name.trim().isEmpty() ? JSONObject.NULL : name);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int status = c.getResponseCode();
            if (status == 201) return Result.STARTED;
            if (status == 409) return Result.ALREADY_OPEN;
            return Result.FAILED;
        } catch (IOException | JSONException e) {
            return Result.FAILED;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
