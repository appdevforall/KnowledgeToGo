package org.iiab.controller.feedback.presentation;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.iiab.controller.R;
import org.iiab.controller.analytics.AnalyticsClient;
import org.iiab.controller.feedback.data.FeedbackConfig;
import org.iiab.controller.feedback.data.MailtoFeedbackTransport;
import org.iiab.controller.feedback.data.MessagingFeedbackSender;
import org.iiab.controller.feedback.data.WorkerFeedbackTransport;
import org.iiab.controller.feedback.domain.FeedbackPayload;
import org.iiab.controller.feedback.domain.FeedbackRenderer;

/**
 * ADFA-5130: routes a feedback report to the channel that can actually consume it.
 *
 * <p>Email carries subject + text body + the screenshot as an attachment. Messaging apps drop
 * {@code EXTRA_TEXT} when an image is attached, so they receive the report as plain text and the
 * screenshot via the clipboard. When there is a screenshot we let the user pick the channel with a
 * small native dialog, because Android's share sheet cannot tell us the chosen app in time to
 * tailor the payload. With no screenshot there is nothing to lose, so it goes straight to email
 * (unchanged). The worker transport, when enabled, POSTs and shows no chooser.
 *
 * <p>The UI is entirely native (the system share sheet plus a two-button dialog) — there is no
 * custom app picker to maintain.
 */
public final class FeedbackShareRouter {

    private FeedbackShareRouter() {
    }

    public static void share(Activity activity, FeedbackPayload payload) {
        if (FeedbackConfig.WORKER.equals(FeedbackConfig.transport(activity))) {
            report(activity, new WorkerFeedbackTransport().send(activity, payload));
            return;
        }
        if (payload.screenshot() == null) {
            report(activity, new MailtoFeedbackTransport().send(activity, payload));
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.feedback_channel_title)
                .setPositiveButton(R.string.feedback_channel_email, (d, w) ->
                        report(activity, new MailtoFeedbackTransport().send(activity, payload)))
                .setNeutralButton(R.string.feedback_channel_share, (d, w) ->
                        report(activity, new MessagingFeedbackSender().send(activity,
                                new FeedbackRenderer().shareText(payload), payload.screenshot())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void report(Context ctx, boolean ok) {
        if (ok) {
            // ADFA-4466 Phase 2: feedback-channel adoption (no content; no-op unless opted in).
            AnalyticsClient.with(ctx).logFeedbackSent();
        } else {
            Toast.makeText(ctx, R.string.feedback_no_email_app, Toast.LENGTH_LONG).show();
        }
    }
}
