package org.iiab.controller.delivery;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.iiab.controller.delivery.data.DeliveryConfig;
import org.iiab.controller.delivery.data.HttpOutboundSender;
import org.iiab.controller.delivery.data.OutboundQueue;
import org.iiab.controller.delivery.domain.OutboundEnvelope;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Network-constrained WorkManager job that drains the durable outbound queue to the
 * Cloudflare Worker. Trims by age/size first, then sends in batches; on any failure it
 * returns retry() so WorkManager backs off and tries again when online.
 */
public final class OutboundSyncWorker extends Worker {

    private static final int BATCH = 50;
    private static final int MAX_ITEMS = 2000;
    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000; // ~30 days

    public OutboundSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!DeliveryConfig.isConfigured(ctx)) {
            return Result.success(); // no endpoint yet: keep items queued, don't spin
        }
        OutboundQueue queue = new OutboundQueue(ctx);
        queue.dropOlderThan(MAX_AGE_MS);
        queue.trimToMax(MAX_ITEMS);

        HttpOutboundSender sender =
                new HttpOutboundSender(DeliveryConfig.getEndpoint(ctx), DeliveryConfig.getToken(ctx));
        while (true) {
            List<OutboundEnvelope> batch = queue.peekBatch(BATCH);
            if (batch.isEmpty()) {
                return Result.success();
            }
            if (!sender.sendBatch(batch)) {
                return Result.retry();
            }
            Set<String> ids = new HashSet<>();
            for (OutboundEnvelope e : batch) {
                ids.add(e.id());
            }
            queue.deleteByIds(ids);
        }
    }
}
