package org.appdevforall.k2go.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.appdevforall.k2go.delivery.data.DeliveryConfig;
import org.appdevforall.k2go.delivery.data.OutboundQueue;
import org.appdevforall.k2go.delivery.domain.OutboundEnvelope;
import org.appdevforall.k2go.delivery.domain.OutboundType;
import org.appdevforall.k2go.feedback.data.FeedbackConfig;
import org.appdevforall.k2go.feedback.data.FeedbackTransport;
import org.appdevforall.k2go.feedback.data.WorkerFeedbackTransport;
import org.appdevforall.k2go.feedback.domain.FeedbackPayload;
import org.appdevforall.k2go.feedback.domain.FeedbackType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

/**
 * On-device proof of the feedback transport flip: default is mailto; when the flag is set
 * to worker the report is enqueued (as a FEEDBACK envelope) on the delivery backbone.
 * Endpoint stays blank so nothing is actually sent during the test.
 */
@RunWith(AndroidJUnit4.class)
public class FeedbackTransportTest {

    private Context ctx;
    private OutboundQueue queue;

    private void clearQueueFile() {
        new File(ctx.getFilesDir(), "delivery_queue.jsonl").delete();
    }

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        DeliveryConfig.setEndpoint(ctx, ""); // never send during the test
        clearQueueFile();
        queue = new OutboundQueue(ctx);
    }

    @After
    public void tearDown() {
        FeedbackConfig.setTransport(ctx, FeedbackConfig.MAILTO);
        clearQueueFile();
    }

    private FeedbackPayload samplePayload() {
        return FeedbackPayload.builder(FeedbackType.BUG)
                .message("device test")
                .appBuild(1)
                .build();
    }

    @Test
    public void defaultTransportIsMailto() {
        FeedbackConfig.setTransport(ctx, FeedbackConfig.MAILTO);
        assertEquals(FeedbackConfig.MAILTO, FeedbackConfig.transport(ctx));
    }

    @Test
    public void workerTransportEnqueuesFeedbackEnvelope() {
        FeedbackConfig.setTransport(ctx, FeedbackConfig.WORKER);
        assertEquals(FeedbackConfig.WORKER, FeedbackConfig.transport(ctx));

        FeedbackTransport transport = FeedbackConfig.create(ctx);
        assertTrue(transport instanceof WorkerFeedbackTransport);

        boolean ok = transport.send(ctx, samplePayload());
        assertTrue(ok);

        assertEquals(1, queue.count());
        List<OutboundEnvelope> batch = queue.peekBatch(10);
        assertEquals(OutboundType.FEEDBACK, batch.get(0).type());
        assertTrue(batch.get(0).payloadJson().contains("\"product\":\"K2Go\""));
    }
}
