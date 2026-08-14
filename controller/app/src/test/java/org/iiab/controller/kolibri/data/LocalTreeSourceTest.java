/*
 * ============================================================================
 * Name        : LocalTreeSourceTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Unit tests for LocalTreeSource against a MockWebServer, mirroring
 *               StudioTreeSourceTest. Verifies the box path and that a miss is a
 *               null the fallback can act on (ADFA-5094).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.iiab.controller.kolibri.domain.TopicNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link LocalTreeSource} against a local {@link MockWebServer},
 * the same treatment {@link StudioTreeSourceTest} gives the internet source.
 *
 * <p>Two things are specific to the local source and worth pinning: it addresses
 * the box's {@code /k2go-api/kolibri/tree/} path, and a 404 — the ordinary case
 * where a channel's metadata is not on the box — comes back as {@code null} so
 * {@link FallbackTreeSource} moves on to Studio, rather than as an error. The
 * JSON parsing itself is Studio's mapper and is exercised there; here the focus
 * is the transport and the miss behaviour.
 *
 * <p>Runs on a plain JVM: {@code android.util.Log} is stubbed by the module's
 * {@code testOptions.unitTests.returnDefaultValues}.
 */
public class LocalTreeSourceTest {

    private static final String CH = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String NODE = "23a7dc9c73635cd2abbd3e8aab13c3ca";

    private MockWebServer server;
    private LocalTreeSource source;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // Trailing slash on purpose: the constructor has to strip it, or every URL
        // it builds carries a double slash.
        source = new LocalTreeSource(server.url("/k2go-api/").toString());
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String treeJson() {
        return "{\"id\":\"" + CH + "\",\"title\":\"3asafeer\",\"kind\":\"topic\","
                + "\"is_leaf\":false,\"lft\":1,\"rght\":360,\"children\":{\"more\":null,"
                + "\"results\":[{\"id\":\"" + NODE + "\",\"title\":\"Beginner\","
                + "\"kind\":\"video\",\"is_leaf\":true,"
                + "\"files\":[{\"file_size\":800,\"preset\":\"high_res_video\"}]}]}}";
    }

    @Test
    public void readsATreeAndHitsTheBoxPath() throws Exception {
        server.enqueue(json(treeJson()));

        TopicNode t = source.fetchTree(CH);
        assertEquals(CH, t.id());
        assertEquals(1, t.children().size());
        assertEquals(800L, t.subtreeBytes());

        RecordedRequest req = server.takeRequest();
        assertEquals("/k2go-api/kolibri/tree/" + CH, req.getPath());
        assertEquals("GET", req.getMethod());
        assertEquals("application/json", req.getHeader("Accept"));
    }

    @Test
    public void aChannelNotImportedIsNullRatherThanAnException() throws Exception {
        // 404 is ordinary here — the channel's metadata simply is not on the box —
        // and must be a null so FallbackTreeSource tries Studio instead.
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"detail\":\"nope\"}"));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void aServerErrorIsNull() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void anIdThatIsNotANodeIdNeverBecomesARequest() throws Exception {
        // Validate before the id reaches the URL path: a value with a slash or a
        // query string would address something else on the box.
        for (String bad : Arrays.asList("../../admin", "abc?x=1", "bisan-sukod",
                "", "   ", null)) {
            assertNull("expected null for " + bad, source.fetchTree(bad));
        }
        assertEquals("no request should have been made", 0, server.getRequestCount());
    }

    @Test
    public void aBodyThatIsNotJsonIsNull() throws Exception {
        server.enqueue(json("<html>nope</html>"));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void aBaseUrlWithoutATrailingSlashWorksToo() throws Exception {
        String noSlash = server.url("/k2go-api").toString();
        server.enqueue(json(treeJson()));
        assertNotNull(new LocalTreeSource(noSlash).fetchTree(CH));
        assertEquals("/k2go-api/kolibri/tree/" + CH, server.takeRequest().getPath());
    }
}
