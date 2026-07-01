package org.iiab.controller.analytics;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.iiab.controller.delivery.data.AnalyticsConsent;
import org.iiab.controller.delivery.data.DeliveryConfig;
import org.iiab.controller.delivery.data.OutboundQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * On-device proof of the privacy gate: with consent OFF, AnalyticsClient enqueues nothing;
 * with consent ON, operational events are enqueued into the durable backbone queue.
 * Endpoint stays blank so nothing is actually sent during the test.
 */
@RunWith(AndroidJUnit4.class)
public class AnalyticsConsentGateTest {

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
        AnalyticsConsent.setEnabled(ctx, false);
        clearQueueFile();
    }

    @Test
    public void nothingEnqueued_whenConsentOff() {
        AnalyticsConsent.setEnabled(ctx, false);
        AnalyticsClient client = AnalyticsClient.with(ctx);
        client.logAppOpened();
        client.logSession(1000L);
        assertEquals(0, queue.count());
    }

    @Test
    public void eventsEnqueued_whenConsentOn() {
        AnalyticsConsent.setEnabled(ctx, true);
        AnalyticsClient client = AnalyticsClient.with(ctx);
        client.logAppOpened();
        client.logSession(1000L);
        assertEquals(2, queue.count());
    }
}
