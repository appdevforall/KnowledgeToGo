package org.appdevforall.k2go.school.data;

import static org.junit.Assert.assertEquals;

import org.appdevforall.k2go.school.domain.ClassLinks;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/** The probe returns the status of the teacher page itself: no redirect is followed. */
public class HttpTeacherPageTest {

    private MockWebServer server;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
    }

    private int status() {
        return new HttpTeacherPage(server.url("/school/teacher/").toString()).status();
    }

    @Test public void servedPageIs200() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<html></html>"));
        assertEquals(200, status());
    }

    @Test public void missingRouteIs404() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertEquals(404, status());
    }

    @Test public void redirectIsNotFollowed() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/home"));
        server.enqueue(new MockResponse().setResponseCode(200));
        assertEquals(302, status());
    }

    @Test public void noServerIsNoAnswer() throws Exception {
        String url = server.url("/school/teacher/").toString();
        server.shutdown();
        assertEquals(ClassLinks.NO_ANSWER, new HttpTeacherPage(url).status());
    }
}
