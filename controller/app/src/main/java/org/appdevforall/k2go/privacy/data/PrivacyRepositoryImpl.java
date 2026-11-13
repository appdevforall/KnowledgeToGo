/*
 * ============================================================================
 * Name        : PrivacyRepositoryImpl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-559. dash-node client for the privacy switches:
 *                 GET  /k2go-api/system/privacy            -> {services:[{service,installed,outbound}]}
 *                 POST /k2go-api/system/privacy/<service>  {outbound} -> {service,installed,outbound,changed}
 *                 POST /k2go-api/system/service/<service>/restart  -> 202 (in-place restart, ADR-5343a)
 *               Localhost-only, like all of /k2go-api.
 * ============================================================================
 */
package org.appdevforall.k2go.privacy.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.privacy.domain.OutboundSwitch;
import org.appdevforall.k2go.privacy.domain.PrivacyException;
import org.appdevforall.k2go.privacy.domain.PrivacyRepository;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PrivacyRepositoryImpl implements PrivacyRepository {

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 15000;

    private final String base;
    private final String restartBase;

    public PrivacyRepositoryImpl() {
        this(BoxEndpoints.API);
    }

    /** {@code apiBase} is the /k2go-api root (tests pass a local server). */
    public PrivacyRepositoryImpl(String apiBase) {
        this.base = apiBase + "/system/privacy";
        this.restartBase = apiBase + "/system/service/";
    }

    @Override
    public List<OutboundSwitch> list() throws PrivacyException {
        try {
            JSONArray arr = new JSONObject(request("GET", base, null)).optJSONArray("services");
            List<OutboundSwitch> out = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(parse(o));
            }
            return out;
        } catch (JSONException e) {
            throw new PrivacyException(PrivacyException.Reason.FAILED, e.getMessage());
        }
    }

    @Override
    public boolean set(String service, boolean outbound) throws PrivacyException {
        try {
            JSONObject body = new JSONObject().put("outbound", outbound);
            return new JSONObject(request("POST", base + "/" + service, body)).optBoolean("changed", true);
        } catch (JSONException e) {
            throw new PrivacyException(PrivacyException.Reason.FAILED, e.getMessage());
        }
    }

    @Override
    public void requestRestart(String service) throws PrivacyException {
        request("POST", restartBase + service + "/restart", new JSONObject());
    }

    private static OutboundSwitch parse(JSONObject o) {
        return new OutboundSwitch(o.optString("service", ""), o.optBoolean("installed", false),
                o.optBoolean("outbound", false), o.optBoolean("pending", false));
    }

    private static String request(String method, String url, JSONObject body) throws PrivacyException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            // A dash-node older than 1.6.0 has no such route: Express answers 404.
            if (code == 404) throw new PrivacyException(PrivacyException.Reason.UNSUPPORTED, "HTTP 404");
            if (!ok) throw new PrivacyException(PrivacyException.Reason.FAILED, "HTTP " + code);
            return text.isEmpty() ? "{}" : text;
        } catch (IOException e) {
            throw new PrivacyException(PrivacyException.Reason.UNREACHABLE, e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }
}
