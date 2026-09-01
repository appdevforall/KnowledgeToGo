package org.appdevforall.k2go.feedback.presentation;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.analytics.AnalyticsClient;
import org.appdevforall.k2go.feedback.data.FeedbackConfig;
import org.appdevforall.k2go.feedback.data.HybridFeedbackSender;
import org.appdevforall.k2go.feedback.data.MailtoFeedbackTransport;
import org.appdevforall.k2go.feedback.data.WorkerFeedbackTransport;
import org.appdevforall.k2go.feedback.domain.EmailContent;
import org.appdevforall.k2go.feedback.domain.FeedbackPayload;
import org.appdevforall.k2go.feedback.domain.FeedbackRenderer;

/**
 * ADFA-5130: hands a feedback report to the right sender.
 *
 * <p>With a screenshot it goes through {@link HybridFeedbackSender} — a single native share that
 * carries text + image so it works across email and messaging in one send (and coaxes even Slack
 * into keeping the text, with a clipboard-text fallback). With no screenshot there is nothing an
 * image-only receiver could strip, so the plain mail path is used (unchanged). The worker transport,
 * when enabled, POSTs and shows no chooser.
 *
 * <p>The UI is entirely native (the system share sheet); there is no custom app picker and no
 * per-app branching.
 */
public final class FeedbackShareRouter {

    private FeedbackShareRouter() {
    }

    public static void share(Activity activity, FeedbackPayload payload) {
        if (FeedbackConfig.WORKER.equals(FeedbackConfig.transport(activity))) {
            report(activity, new WorkerFeedbackTransport().send(activity, payload));
            return;
        }
        EmailContent content = new FeedbackRenderer().render(payload);
        if (content.attachmentPath() == null) {
            report(activity, new MailtoFeedbackTransport().send(activity, payload));
            return;
        }
        report(activity, new HybridFeedbackSender().send(activity, content));
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
