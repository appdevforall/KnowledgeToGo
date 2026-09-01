package org.appdevforall.k2go.feedback.data;

import android.content.Context;

import org.appdevforall.k2go.feedback.domain.EmailContent;
import org.appdevforall.k2go.feedback.domain.FeedbackPayload;
import org.appdevforall.k2go.feedback.domain.FeedbackRenderer;

/** Default transport: renders the report to an email and opens the user's mail app. */
public final class MailtoFeedbackTransport implements FeedbackTransport {

    @Override
    public boolean send(Context ctx, FeedbackPayload payload) {
        EmailContent content = new FeedbackRenderer().render(payload);
        return new EmailFeedbackSender().send(ctx, content);
    }
}
