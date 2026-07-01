package org.iiab.controller.delivery.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.iiab.controller.delivery.DeliveryManager;
import org.iiab.controller.delivery.data.DeliveryConfig;
import org.iiab.controller.delivery.data.InstallId;
import org.iiab.controller.delivery.data.OutboundQueue;
import org.json.JSONObject;

/**
 * DEBUG-ONLY. Exercises the delivery backbone from adb, with no app UI. Never ships in
 * release (it lives in src/debug). Example:
 *
 * <pre>
 * adb shell am broadcast \
 *   -a org.iiab.controller.DEBUG_DELIVERY \
 *   -n org.iiab.controller/org.iiab.controller.delivery.debug.DebugDeliveryReceiver \
 *   --es url https://webhook.site/YOUR-ID \
 *   --es token demo-token \
 *   --es msg "Hola desde el campo"
 * </pre>
 *
 * It points {@link DeliveryConfig} at your endpoint, enqueues one FEEDBACK envelope, and
 * schedules the sync. Toggle airplane mode to see store-and-forward end to end: the item
 * stays queued while offline and is POSTed to your endpoint when connectivity returns.
 * Watch the queue count / endpoint in logcat: {@code adb logcat -s IIAB-DebugDelivery}.
 */
public final class DebugDeliveryReceiver extends BroadcastReceiver {

    private static final String TAG = "IIAB-DebugDelivery";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();

        String url = intent.getStringExtra("url");
        String token = intent.getStringExtra("token");
        String msg = intent.getStringExtra("msg");
        if (msg == null) {
            msg = "K2Go debug delivery test";
        }

        if (url != null && !url.isEmpty()) {
            DeliveryConfig.setEndpoint(app, url);
        }
        if (token != null) {
            DeliveryConfig.setToken(app, token);
        }

        try {
            JSONObject payload = new JSONObject()
                    .put("product", "K2Go")
                    .put("type", "bug")
                    .put("message", msg)
                    .put("install_id", InstallId.get(app))
                    .put("ts", System.currentTimeMillis());
            DeliveryManager.with(app).enqueueFeedback(payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "failed to enqueue debug delivery", e);
            return;
        }

        int count = new OutboundQueue(app).count();
        Log.i(TAG, "enqueued debug FEEDBACK; endpoint=" + DeliveryConfig.getEndpoint(app)
                + " queueCount=" + count
                + " (sync scheduled; delivers when connectivity returns)");
    }
}
