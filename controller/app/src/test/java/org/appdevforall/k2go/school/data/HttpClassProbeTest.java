package org.appdevforall.k2go.school.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.appdevforall.k2go.school.domain.ClassProbe;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/** The probe tells "no class open" (a box answered) from "no answer" (no box there). */
public class HttpClassProbeTest {

    private MockWebServer server;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
    }

    private ClassProbe.Answer ask() {
        return new HttpClassProbe(server.getPort()).ask(server.getHostName());
    }

    @Test public void anOpenClassGivesItsCode() {
        server.enqueue(new MockResponse().setBody("{\"open\":true,\"code\":\"4F7K\"}"));
        assertEquals("4F7K", ask().code);
    }

    @Test public void aClosedClassIsAnAnswerWithNoCode() {
        server.enqueue(new MockResponse().setBody("{\"open\":false,\"code\":null}"));
        ClassProbe.Answer a = ask();
        assertNotNull(a);
        assertNull(a.code);
    }

    @Test public void anotherServerOrAnErrorIsNoAnswer() {
        server.enqueue(new MockResponse().setBody("<html>hello</html>"));
        assertNull(ask());
        server.enqueue(new MockResponse().setResponseCode(502));
        assertNull(ask());
    }

    @Test public void noServerIsNoAnswer() throws Exception {
        int port = server.getPort();
        String host = server.getHostName();
        server.shutdown();
        assertNull(new HttpClassProbe(port).ask(host));
    }
}
