package org.iiab.controller.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestListenableWorkerBuilder;

import org.iiab.controller.delivery.data.DeliveryConfig;
import org.iiab.controller.delivery.data.OutboundQueue;
import org.iiab.controller.delivery.domain.OutboundEnvelope;
import org.iiab.controller.delivery.domain.OutboundType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * On-device (instrumented) test of the full store-and-forward backbone: the real durable
 * queue + the real WorkManager Worker + HTTP, against a local MockWebServer. Proves:
 *  - a 2xx drains the queue (the batch is delivered with the auth header), and
 *  - a 5xx makes the Worker retry and RETAIN the items (nothing is lost).
 *
 * Run on a connected device/emulator: ./gradlew connectedDebugAndroidTest
 * (or right-click -> Run in Android Studio).
 */
@RunWith(AndroidJUnit4.class)
public class DeliveryBackboneInstrumentedTest {

    private Context ctx;
    private MockWebServer server;
    private OutboundQueue queue;

    private void clearQueueFile() {
        new File(ctx.getFilesDir(), "delivery_queue.jsonl").delete();
    }

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        clearQueueFile();
        server = new MockWebServer();
        server.start();
        DeliveryConfig.setEndpoint(ctx, server.url("/ingest").toString());
        DeliveryConfig.setToken(ctx, "device-token");
        queue = new OutboundQueue(ctx);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
        DeliveryConfig.setEndpoint(ctx, "");
        DeliveryConfig.setToken(ctx, "");
        clearQueueFile();
    }

    private void enqueue(int n) {
        for (int i = 0; i < n; i++) {
            queue.enqueue(new OutboundEnvelope(
                    UUID.randomUUID().toString(), OutboundType.FEEDBACK,
                    "{\"product\":\"K2Go\",\"message\":\"item" + i + "\"}",
                    System.currentTimeMillis()));
        }
    }

    private OutboundSyncWorker worker() {
        return TestListenableWorkerBuilder.from(ctx, OutboundSyncWorker.class).build();
    }

    @Test
    public void queueDrains_whenServerAccepts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        enqueue(3);
        assertEquals(3, queue.count());

        ListenableWorker.Result result = worker().doWork();

        assertEquals(ListenableWorker.Result.success(), result);
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("device-token", req.getHeader("x-k2go-token"));
        assertEquals(0, queue.count());
    }

    @Test
    public void queueRetained_whenServerErrors() {
        server.enqueue(new MockResponse().setResponseCode(500));
        enqueue(3);

        ListenableWorker.Result result = worker().doWork();

        assertEquals(ListenableWorker.Result.retry(), result);
        assertEquals(3, queue.count());
    }
}
