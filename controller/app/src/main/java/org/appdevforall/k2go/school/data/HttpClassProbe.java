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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One GET of {@code /school/state} on a box: this phone's box, or a teacher box on the Wi-Fi. */
public final class HttpClassProbe implements ClassProbe {

    private static final int MAX_BODY = 256;

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
                return ClassLinks.isState(body) ? new Answer(ClassLinks.codeFromState(body)) : null;
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
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
