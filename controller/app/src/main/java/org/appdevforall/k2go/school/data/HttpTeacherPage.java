/*
 * ============================================================================
 * Name        : HttpTeacherPage.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: GET of the teacher page on this phone's box (nginx :8085).
 * ============================================================================
 */
package org.appdevforall.k2go.school.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.domain.ClassLinks;
import org.appdevforall.k2go.school.domain.TeacherPage;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Reads only the status line: the page body is for the WebView, not for this check. */
public final class HttpTeacherPage implements TeacherPage {

    private final String url;

    public HttpTeacherPage() { this(ClassLinks.teacherUrl(BoxEndpoints.BASE)); }

    HttpTeacherPage(String url) { this.url = url; }   // tests

    @Override
    public int status() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod("GET");
            return c.getResponseCode();
        } catch (IOException e) {
            return ClassLinks.NO_ANSWER;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
