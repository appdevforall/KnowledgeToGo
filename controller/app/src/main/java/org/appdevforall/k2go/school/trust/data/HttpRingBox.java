/*
 * ============================================================================
 * Name        : HttpRingBox.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School trust: the setup ring routes of the box (dash-node school/ring.ts).
 * ============================================================================
 */
package org.appdevforall.k2go.school.trust.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.trust.domain.SchoolRing;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The box of this phone (localhost, /school/teacher/ring/*) and the box of the phone that opened a
 * ring (its address, /school/ring/*). POST bodies only: no nonce or id in an address.
 */
public final class HttpRingBox implements SchoolRing.Box {

    private static final int MAX_ANSWER = 16 * 1024;
    private final String local;

    public HttpRingBox() { this(BoxEndpoints.BASE); }

    HttpRingBox(String localBase) { this.local = localBase; }   // tests

    @Override
    public boolean open(SchoolRing.Kind kind, String pin, String rootToken) {
        JSONObject b = new JSONObject();
        try {
            b.put("kind", kind == SchoolRing.Kind.TEACHER ? "teacher" : "student").put("pin", pin).put("root", rootToken);
        } catch (Exception e) {
            return false;
        }
        return call("POST", local + "/school/teacher/ring/open", b).status == 204;
    }

    @Override
    public SchoolRing.View view() {
        Answer a = call("GET", local + "/school/teacher/ring", null);
        if (a.status != 200 || a.json == null) return new SchoolRing.View(false, 0, null, new ArrayList<>());
        List<SchoolRing.Request> list = new ArrayList<>();
        JSONArray rs = a.json.optJSONArray("requests");
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.optJSONObject(i);
                if (r == null) continue;
                list.add(new SchoolRing.Request(r.optString("id"), r.optString("key"),
                        r.optBoolean("approved"), r.optBoolean("refused")));
            }
        }
        Object by = a.json.opt("closedBy");
        return new SchoolRing.View(a.json.optBoolean("open"), a.json.optLong("closesIn"),
                by instanceof String ? (String) by : null, list);
    }

    @Override
    public boolean answer(String id, String certOrNull) {
        JSONObject b = new JSONObject();
        try {
            b.put("id", id).put("cert", certOrNull == null ? JSONObject.NULL : certOrNull);
        } catch (Exception e) {
            return false;
        }
        return call("POST", local + "/school/teacher/ring/answer", b).status == 204;
    }

    @Override
    public void close() {
        call("POST", local + "/school/teacher/ring/close", new JSONObject());
    }

    @Override
    public SchoolRing.Reply join(SchoolRing.Found ring, String nonce, String teacherKey, String proof) {
        JSONObject b = new JSONObject();
        try {
            b.put("nonce", nonce).put("proof", proof);
            if (teacherKey != null) b.put("key", teacherKey);
        } catch (Exception e) {
            return SchoolRing.Reply.of(SchoolRing.Reply.Status.FAILED);
        }
        return reply(call("POST", remote(ring) + "/school/ring/join", b));
    }

    @Override
    public SchoolRing.Reply result(SchoolRing.Found ring, String id, String nonce) {
        JSONObject b = new JSONObject();
        try {
            b.put("id", id).put("nonce", nonce);
        } catch (Exception e) {
            return SchoolRing.Reply.of(SchoolRing.Reply.Status.FAILED);
        }
        return reply(call("POST", remote(ring) + "/school/ring/result", b));
    }

    private static String remote(SchoolRing.Found ring) {
        return "http://" + ring.address + ":" + ring.port;
    }

    private static SchoolRing.Reply reply(Answer a) {
        switch (a.status) {
            case 200:
                if (a.json == null) break;
                return new SchoolRing.Reply(SchoolRing.Reply.Status.OK, str(a.json, "root"), str(a.json, "cert"),
                        str(a.json, "mac"), null);
            case 202:
                return new SchoolRing.Reply(SchoolRing.Reply.Status.WAITING, null, null, null,
                        a.json == null ? null : str(a.json, "id"));
            case 403:
                return SchoolRing.Reply.of(a.json != null && "refused".equals(a.json.opt("error"))
                        ? SchoolRing.Reply.Status.REFUSED : SchoolRing.Reply.Status.WRONG_PIN);
            case 404:
                return SchoolRing.Reply.of(SchoolRing.Reply.Status.NO_RING);
            case 429:
                return SchoolRing.Reply.of(SchoolRing.Reply.Status.TOO_MANY_TRIES);
            default:
                break;
        }
        return SchoolRing.Reply.of(SchoolRing.Reply.Status.FAILED);
    }

    private static String str(JSONObject o, String name) {
        Object x = o.opt(name);
        return x instanceof String ? (String) x : null;
    }

    private static final class Answer {
        final int status;
        final JSONObject json;

        Answer(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }
    }

    private static Answer call(String method, String url, JSONObject body) {
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
