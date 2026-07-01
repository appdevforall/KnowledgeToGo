package org.iiab.controller.feedback.data;

import android.content.Context;

import org.iiab.controller.feedback.domain.FeedbackPayload;

/**
 * Where a feedback report goes. Two implementations exist: {@link MailtoFeedbackTransport}
 * (the current default) and {@link WorkerFeedbackTransport} (the delivery backbone).
 * {@link FeedbackConfig} selects one via a flag, so switching is a config change — no
 * rework in the form. See ADFA-4507.
 */
public interface FeedbackTransport {

    /**
     * Hand off the report.
     * @return true if it was accepted (mail app opened, or queued for delivery);
     *         false if it could not be handed off (e.g. no mail app for mailto).
     */
    boolean send(Context ctx, FeedbackPayload payload);
}
