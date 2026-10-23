package org.appdevforall.k2go.feedback.data;

import android.content.Context;

import org.appdevforall.k2go.feedback.domain.FeedbackPayload;

/**
 * Where a feedback report goes. The only implementation is {@link MailtoFeedbackTransport}: it
 * hands the report to the user's mail app, so the app itself sends nothing off the device.
 */
public interface FeedbackTransport {

    /**
     * Hand off the report.
     * @return true if it was accepted (mail app opened, or queued for delivery);
     *         false if it could not be handed off (e.g. no mail app for mailto).
     */
    boolean send(Context ctx, FeedbackPayload payload);
}
