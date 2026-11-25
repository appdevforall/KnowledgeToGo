/*
 * ============================================================================
 * Name        : HttpClassState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: reads "the class is open" from this phone's box.
 * ============================================================================
 */
package org.appdevforall.k2go.school.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.ClassState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One GET of {@code /school/teacher/state} on the local box (localhost only, nginx allow list). */
public final class HttpClassState implements ClassState {

    private static final int MAX_BODY = 256;

    private final String url;

    public HttpClassState() { this(ClassLinks.teacherStateUrl(BoxEndpoints.BASE)); }

    HttpClassState(String url) { this.url = url; }   // tests

    @Override
    public boolean open() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream()) {
                return ClassLinks.openFromState(read(in));
            }
        } catch (IOException e) {
            return false;
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
