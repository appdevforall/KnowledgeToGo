package org.appdevforall.k2go.privacy.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.appdevforall.k2go.privacy.domain.OutboundSwitch;
import org.appdevforall.k2go.privacy.domain.PrivacyException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PrivacyRepositoryImplTest {

    private MockWebServer server;
    private PrivacyRepositoryImpl repo;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        repo = new PrivacyRepositoryImpl(server.url("/k2go-api").toString().replaceAll("/$", ""));
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void listParsesEachService() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"services\":["
                + "{\"service\":\"kolibri\",\"installed\":true,\"outbound\":false},"
                + "{\"service\":\"forgejo\",\"installed\":false,\"outbound\":false}]}"));
        List<OutboundSwitch> out = repo.list();
        assertEquals(2, out.size());
        assertEquals("kolibri", out.get(0).service());
        assertTrue(out.get(0).installed());
        assertFalse(out.get(0).outbound());
        assertFalse(out.get(1).installed());
        assertEquals("/k2go-api/system/privacy", server.takeRequest().getPath());
    }

    @Test
    public void setPostsTheValueAndReturnsTheNewState() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"service\":\"forgejo\",\"installed\":true,\"outbound\":true,\"changed\":true}"));
        OutboundSwitch s = repo.set("forgejo", true);
        assertTrue(s.outbound());
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/k2go-api/system/privacy/forgejo", r.getPath());
        assertEquals("{\"outbound\":true}", r.getBody().readUtf8());
    }

    @Test
    public void notFoundMeansAnOlderDashboard() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("Cannot GET"));
        assertReason(PrivacyException.Reason.UNSUPPORTED);
    }

    @Test
    public void serverErrorIsAFailure() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"write failed\"}"));
        assertReason(PrivacyException.Reason.FAILED);
    }

    @Test
    public void noAnswerIsUnreachable() throws Exception {
        server.shutdown();
        assertReason(PrivacyException.Reason.UNREACHABLE);
    }

    private void assertReason(PrivacyException.Reason expected) {
        try {
            repo.list();
            fail("expected " + expected);
        } catch (PrivacyException e) {
            assertEquals(expected, e.reason());
        }
    }
}
