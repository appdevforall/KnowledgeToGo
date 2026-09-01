package org.appdevforall.k2go.delivery;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.appdevforall.k2go.delivery.data.AnalyticsConsent;
import org.appdevforall.k2go.delivery.data.OutboundQueue;
import org.appdevforall.k2go.delivery.domain.OutboundEnvelope;
import org.appdevforall.k2go.delivery.domain.OutboundType;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the shared delivery backbone: enqueue an envelope into the durable
 * queue and schedule a network-constrained WorkManager sync (store-and-forward).
 * Analytics is gated on {@link AnalyticsConsent}; feedback is not (it is an explicit action).
 */
public final class DeliveryManager {

    private static final String UNIQUE_WORK = "k2go-outbound-sync";

    private final Context app;

    private DeliveryManager(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public static DeliveryManager with(Context ctx) {
        return new DeliveryManager(ctx);
    }

    public void enqueueFeedback(String payloadJson) {
        enqueue(OutboundType.FEEDBACK, payloadJson);
    }

    /** No-op unless the operator has opted in to analytics. */
    public void enqueueAnalytics(String payloadJson) {
        if (!AnalyticsConsent.isEnabled(app)) {
            return;
        }
        enqueue(OutboundType.ANALYTICS, payloadJson);
    }

    private void enqueue(OutboundType type, String payloadJson) {
        OutboundEnvelope e = new OutboundEnvelope(
                UUID.randomUUID().toString(), type, payloadJson, System.currentTimeMillis());
        new OutboundQueue(app).enqueue(e);
        scheduleSync();
    }

    private void scheduleSync() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OutboundSyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request);
    }
}
