package org.iiab.controller.kolibri.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.iiab.controller.kolibri.domain.TopicNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link StudioTreeSource} against a local {@link MockWebServer},
 * following {@code HttpOutboundSenderTest}.
 *
 * <p>This is the only class in the Kolibri catalog slice that touches the
 * network, so it is the only one whose failure modes cannot be reasoned about
 * from pure logic: what it does with a 404, with a body that is not JSON, with a
 * response too large to hold on a phone, and — the one that matters for security
 * — what it does with a node id that is not a node id.
 *
 * <p>Runs on a plain JVM: {@code android.util.Log} is stubbed by the module's
 * {@code testOptions.unitTests.returnDefaultValues}.
 */
public class StudioTreeSourceTest {

    private static final String CH = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String NODE = "23a7dc9c73635cd2abbd3e8aab13c3ca";

    private MockWebServer server;
    private StudioTreeSource source;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // Trailing slash on purpose: the constructor has to strip it, or every
        // URL it builds carries a double slash.
        source = new StudioTreeSource(server.url("/").toString());
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
    public void readsATreeAndHitsTheExpectedPath() throws Exception {
        server.enqueue(json(treeJson()));

        TopicNode t = source.fetchTree(CH);
        assertEquals(CH, t.id());
        assertEquals(1, t.children().size());
        assertEquals(800L, t.subtreeBytes());
        assertEquals(179, t.descendantCount());

        RecordedRequest req = server.takeRequest();
        assertEquals("/api/public/v2/contentnode_tree/" + CH, req.getPath());
        assertEquals("GET", req.getMethod());
        assertEquals("application/json", req.getHeader("Accept"));
    }

    @Test
    public void aHyphenatedIdIsNormalisedBeforeItReachesTheUrl() throws Exception {
        server.enqueue(json(treeJson()));
        source.fetchTree("c150ea1d-6949-5d37-b5b0-ac6f017e9bfb");
        assertEquals("/api/public/v2/contentnode_tree/" + CH,
                server.takeRequest().getPath());
    }

    @Test
    public void anIdThatIsNotANodeIdNeverBecomesARequest() throws Exception {
        // The point of validating before building the URL: a value carrying a
        // slash or a query string would address something else entirely.
        for (String bad : Arrays.asList("../../admin", "abc?x=1", "bisan-sukod",
                "", "   ", null)) {
            assertNull("expected null for " + bad, source.fetchTree(bad));
        }
        assertEquals("no request should have been made", 0, server.getRequestCount());
    }

    @Test
    public void aNotFoundIsNullRatherThanAnException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"detail\":\"nope\"}"));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void aServerErrorIsNull() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void aBodyThatIsNotJsonIsNull() throws Exception {
        server.enqueue(json("<html>maintenance</html>"));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void anEmptyBodyIsNull() throws Exception {
        server.enqueue(json(""));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void jsonWithoutAUsableIdIsNull() throws Exception {
        server.enqueue(json("{\"title\":\"no id here\"}"));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void anOversizedResponseIsRefusedInsteadOfFillingTheHeap() throws Exception {
        // A phone must not try to hold an unbounded body. 9 MB is over the 8 MB
        // ceiling; the read aborts and the caller gets null.
        byte[] filler = new byte[9 * 1024 * 1024];
        Arrays.fill(filler, (byte) 'x');
        server.enqueue(json("{\"pad\":\"" + new String(filler, StandardCharsets.UTF_8) + "\"}"));
        assertNull(source.fetchTree(CH));
    }

    @Test
    public void aPagedChildLevelComesBackWithAnUnknownSubtreeSize() throws Exception {
        server.enqueue(json("{\"id\":\"" + CH + "\",\"kind\":\"topic\",\"is_leaf\":false,"
                + "\"children\":{\"more\":{\"cursor\":\"abc\"},\"results\":[{\"id\":\""
                + NODE + "\",\"is_leaf\":true,\"files\":[{\"file_size\":10}]}]}}"));
        TopicNode t = source.fetchTree(CH);
        assertEquals(1, t.children().size());
        assertFalse(t.hasSubtreeSize());
    }

    @Test
    public void aBaseUrlWithoutATrailingSlashWorksToo() throws Exception {
        String noSlash = server.url("/").toString().replaceAll("/$", "");
        server.enqueue(json(treeJson()));
        assertTrue(new StudioTreeSource(noSlash).fetchTree(CH) != null);
        assertEquals("/api/public/v2/contentnode_tree/" + CH,
                server.takeRequest().getPath());
    }
}
