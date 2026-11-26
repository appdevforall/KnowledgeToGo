/*
 * ============================================================================
 * Name        : HttpClassProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: reads the open class (its code) from a box.
 * ============================================================================
 */
package org.appdevforall.k2go.school.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.ClassProbe;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One GET of {@code /school/state} on a box: this phone's box, or a teacher box on the Wi-Fi. */
public final class HttpClassProbe implements ClassProbe {

    // The state with a class name of 20 code points (4 bytes each in UTF-8) and its escapes.
    private static final int MAX_BODY = 512;

    private final int port;

    public HttpClassProbe() { this(BoxEndpoints.PORT); }

    HttpClassProbe(int port) { this.port = port; }   // tests

    @Override
    public Answer ask(String host) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ClassLinks.stateUrl(host, port)).openConnection();
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                String body = read(in);
                return ClassLinks.isState(body) ? answer(body) : null;
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * The name and the age are extras for the class list: a box with no name or age (an older
     * dash-node), or a value of the wrong type, still gives its code.
     */
    static Answer answer(String body) {
        String code = ClassLinks.codeFromState(body);
        if (code == null) return new Answer(null);
        try {
            JSONObject o = new JSONObject(body);
            Object rawName = o.opt("name");
            String name = rawName instanceof String && !((String) rawName).trim().isEmpty() ? (String) rawName : null;
            Object rawAge = o.opt("ageSec");
            long age = rawAge instanceof Number ? Math.max(-1, Math.min(Integer.MAX_VALUE, ((Number) rawAge).longValue())) : -1;
            return new Answer(code, name, age);
        } catch (JSONException e) {
            return new Answer(code);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[MAX_BODY];
        int n;
        while (out.size() < MAX_BODY && (n = in.read(buf, 0, MAX_BODY - out.size())) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
