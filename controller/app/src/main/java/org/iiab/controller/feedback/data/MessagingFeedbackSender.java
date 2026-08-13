package org.iiab.controller.feedback.data;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.iiab.controller.R;

import java.io.File;

/**
 * ADFA-5130: the "share to another app" channel. Sends the report as plain text ({@code EXTRA_TEXT}
 * on an {@code ACTION_SEND text/plain}, with no image stream) so messaging apps keep it — they drop
 * {@code EXTRA_TEXT} when a stream is attached, which is why sharing to Slack used to deliver only the
 * screenshot. Keeping the report as real, selectable, accessible text (instead of baking it into an
 * image) is the whole point.
 *
 * <p>As a best-effort convenience the screenshot is copied to the clipboard so the user can paste it
 * where the app/keyboard support rich-content paste. That is a bonus, not a guarantee (paste support
 * varies by app and keyboard); correctness never depends on it — the text share carries the report.
 * The UI is the native chooser; there is no custom picker.
 */
public final class MessagingFeedbackSender {

    /** @return true if the share chooser was launched; false otherwise. */
    public boolean send(Context ctx, String text, String screenshotPath) {
        try {
            boolean copied = copyScreenshotToClipboard(ctx, screenshotPath);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            if (copied) {
                // Shown just before leaving the app: a toast survives the transition, whereas a
                // snackbar in our window would not (the user lands in the other app).
                Toast.makeText(ctx, R.string.feedback_share_copied, Toast.LENGTH_LONG).show();
            }
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.feedback_send)));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Best-effort: put the screenshot on the clipboard as an image URI. Never throws. */
    private static boolean copyScreenshotToClipboard(Context ctx, String screenshotPath) {
        if (screenshotPath == null) {
            return false;
        }
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".provider", new File(screenshotPath));
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newUri(ctx.getContentResolver(), "screenshot", uri));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
