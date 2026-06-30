package org.iiab.controller.feedback.data;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.iiab.controller.R;
import org.iiab.controller.feedback.domain.EmailContent;

/**
 * Email-first (v1) transport: hands a rendered {@link EmailContent} to the user's mail
 * app via an {@code ACTION_SENDTO} mailto intent. Data layer; no domain logic here.
 *
 * <p>When the Cloudflare Worker endpoint exists, a sibling {@code HttpFeedbackSender}
 * will POST the same payload as JSON — this class stays untouched (additive migration).
 */
public final class EmailFeedbackSender {

    /** @return true if a mail app handled the intent; false if none is available. */
    public boolean send(Context ctx, EmailContent email) {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:"));
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{email.recipient()});
        intent.putExtra(Intent.EXTRA_SUBJECT, email.subject());
        intent.putExtra(Intent.EXTRA_TEXT, email.body());
        try {
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.feedback_send)));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
