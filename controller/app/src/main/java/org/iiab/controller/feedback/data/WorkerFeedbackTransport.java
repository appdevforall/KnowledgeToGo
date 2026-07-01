package org.iiab.controller.feedback.data;

import android.content.Context;

import org.iiab.controller.delivery.DeliveryManager;
import org.iiab.controller.delivery.data.InstallId;
import org.iiab.controller.feedback.domain.FeedbackJson;
import org.iiab.controller.feedback.domain.FeedbackPayload;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Worker transport: renders the report to JSON, stamps the anonymous install id, and
 * enqueues it on the shared delivery backbone (store-and-forward). No mail app or email
 * account is required; the backbone POSTs it to the Cloudflare Worker when connectivity
 * returns. Always returns true — the report is safely queued even while offline.
 */
public final class WorkerFeedbackTransport implements FeedbackTransport {

    @Override
    public boolean send(Context ctx, FeedbackPayload payload) {
        JSONObject json = new FeedbackJson().render(payload);
        try {
            json.put("install_id", InstallId.get(ctx));
        } catch (JSONException ignored) {
            // install_id is best-effort; the report is still valid without it.
        }
        DeliveryManager.with(ctx).enqueueFeedback(json.toString());
        return true;
    }
}
