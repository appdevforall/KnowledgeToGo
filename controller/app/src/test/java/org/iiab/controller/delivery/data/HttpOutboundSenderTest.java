package org.iiab.controller.delivery.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.delivery.domain.OutboundEnvelope;
import org.iiab.controller.delivery.domain.OutboundType;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * JVM unit test (runs in CI via testDebugUnitTest) proving the HTTP contract of the
 * store-and-forward sender against a local MockWebServer: batched POST, auth header,
 * 2xx == delivered, 5xx == retry, and no network hit when unconfigured.
 */
public class HttpOutboundSenderTest {

    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static OutboundEnvelope env(String id) {
        return new OutboundEnvelope(id, OutboundType.FEEDBACK,
                "{\"product\":\"K2Go\",\"message\":\"hi\"}", 1000L);
    }

    @Test
    public void sendBatch_postsItemsWithToken_andReturnsTrue_on2xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        HttpOutboundSender sender = new HttpOutboundSender(server.url("/ingest").toString(), "tkn-123");
        List<OutboundEnvelope> batch = Arrays.asList(env("a"), env("b"));

        assertTrue(sender.sendBatch(batch));

        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("tkn-123", req.getHeader("x-k2go-token"));

        JSONObject body = new JSONObject(req.getBody().readUtf8());
        JSONArray items = body.getJSONArray("items");
        assertEquals(2, items.length());
        assertEquals("a", items.getJSONObject(0).getString("id"));
        assertEquals("feedback", items.getJSONObject(0).getString("type"));
        assertEquals("K2Go", items.getJSONObject(0).getJSONObject("payload").getString("product"));
    }

    @Test
    public void sendBatch_returnsFalse_on5xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));

        HttpOutboundSender sender = new HttpOutboundSender(server.url("/ingest").toString(), "t");
        assertFalse(sender.sendBatch(Collections.singletonList(env("a"))));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void sendBatch_returnsFalse_andSkipsNetwork_whenEndpointBlank() {
        HttpOutboundSender sender = new HttpOutboundSender("", "t");
        assertFalse(sender.sendBatch(Collections.singletonList(env("a"))));
        assertEquals(0, server.getRequestCount());
    }
}
