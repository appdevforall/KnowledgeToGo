package org.iiab.controller.feedback.data;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.iiab.controller.R;
import org.iiab.controller.feedback.domain.EmailContent;

import java.io.File;

/**
 * ADFA-5130: one "hybrid" share that carries the report text AND the screenshot in a single send,
 * built so that even a receiver with a rigid image-only path (Slack) is nudged to keep the text —
 * without special-casing any app by package name (brittle) and without baking the text into the
 * image (destroys accessibility). Well-behaved apps (email, Telegram, Signal, WhatsApp) show image
 * + caption from the same intent.
 *
 * <p>Three levers:
 * <ul>
 *   <li><b>Wildcard MIME {@code *}{@code /*}</b> — denies Slack its "image-only upload" fast path and
 *       forces the general composer, which iterates the whole Extras bundle and so keeps
 *       {@code EXTRA_TEXT}.</li>
 *   <li><b>Composite {@link ClipData.Item}</b> — text and the image Uri are bound in one clip item
 *       (dual MIME), so a receiver that reads the primary ClipData instead of the Extras still finds
 *       both.</li>
 *   <li><b>Clipboard text fallback</b> — the report text is also placed on the clipboard as a
 *       deterministic floor. Slack has a documented ~500-char parsing bug that can truncate a long
 *       body (ours exceeds that), so the user can paste the full text if it did not come through.
 *       Text on the clipboard stays real, selectable and accessible.</li>
 * </ul>
 * The UI is the native share sheet; there is no custom picker.
 */
public final class HybridFeedbackSender {

    /** @return true if the share chooser was launched. */
    public boolean send(Activity activity, EmailContent content) {
        try {
            String body = content.body();
            boolean copied = copyTextToClipboard(activity, body);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("*/*");   // deny Slack the image-only path; keep messaging + email in the sheet
            intent.putExtra(Intent.EXTRA_TEXT, body);
            intent.putExtra(Intent.EXTRA_SUBJECT, content.subject());
            intent.putExtra(Intent.EXTRA_TITLE, content.subject());   // sharesheet preview title
            if (content.recipient() != null) {
                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{content.recipient()});
            }
            if (content.attachmentPath() != null) {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        activity, activity.getPackageName() + ".provider", new File(content.attachmentPath()));
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                // Bind text + image in one clip item so a ClipData-first parser still gets both.
                intent.setClipData(new ClipData(
                        "feedback",
                        new String[]{"text/plain", "image/jpeg"},
                        new ClipData.Item(body, (Intent) null, uri)));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (copied) {
                // Shown just before leaving the app: a toast survives the transition, whereas a
                // snackbar in our window would not (the user lands in the other app).
                Toast.makeText(activity, R.string.feedback_share_copied, Toast.LENGTH_LONG).show();
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.feedback_send)));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Best-effort: put the report text on the clipboard. Never throws. */
    private static boolean copyTextToClipboard(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText("K2Go feedback", text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
