package org.iiab.controller.feedback.data;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import org.iiab.controller.R;
import org.iiab.controller.feedback.domain.EmailContent;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * ADFA-5130: shares a feedback report so the text is never lost — text is prioritised over the
 * screenshot.
 *
 * <p>Every receiver gets the rich intent (screenshot + text): email, WhatsApp, Telegram and Signal
 * all keep both. Slack is the lone exception — it drops {@code EXTRA_TEXT} whenever an image stream
 * is attached — so Slack alone is handed a text-only intent (no stream, so it keeps the full
 * report). This is done entirely through the native share sheet (no custom picker): Slack's rich
 * entry is removed with {@code EXTRA_EXCLUDE_COMPONENTS} and a text-only one is added with
 * {@code EXTRA_INITIAL_INTENTS}. See {@link #addSlackTextOnlyOverride}.
 *
 * <p><b>Accepted trade-off:</b> excluding Slack's component also removes its Direct Share targets
 * (the conversation shortcuts), so from Slack the user picks the recipient inside the app. We take
 * that over losing the text: the report text is the accessible, machine-usable part; the screenshot
 * is supplementary. The Android share sheet offers no way to keep Direct Share and override the
 * payload at the same time.
 *
 * <p><b>Only Slack is special-cased</b> (matched by package name). If another receiver ever turns
 * out to have the same defect, add its package to the match — the mechanism generalises without new
 * code. If Slack is renamed or absent the override simply does not apply, Slack falls back to the
 * rich intent, and the report text is on the clipboard as a deterministic, accessible fallback.
 *
 * <p><b>Rejected alternatives:</b> baking the text into the image (destroys accessibility for screen
 * readers); a custom picker or a per-app dictionary for every messaging app (maintenance sink); and
 * an earlier wildcard-type + composite-{@code ClipData} "coercion" meant to make Slack keep the
 * text — it did not change Slack's behaviour on a real device, so Slack now gets an explicit
 * text-only intent instead.
 */
public final class HybridFeedbackSender {

    /** Slack's Android package is {@code com.Slack}; matched case-insensitively as a substring so
     *  the exact casing does not matter and a minor rename still catches it. */
    private static final String SLACK_HINT = "slack";

    /** @return true if the share chooser was launched. */
    public boolean send(Activity activity, EmailContent content) {
        try {
            String body = content.body();
            boolean copied = copyTextToClipboard(activity, body);

            Intent rich = buildRichIntent(activity, content, body);
            Intent chooser = Intent.createChooser(rich, activity.getString(R.string.feedback_send));
            addSlackTextOnlyOverride(activity, rich, chooser, body, content.subject());

            if (copied) {
                Toast.makeText(activity, R.string.feedback_share_copied, Toast.LENGTH_LONG).show();
            }
            activity.startActivity(chooser);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The default: text + screenshot, kept by email and standards-respecting messaging apps. */
    private static Intent buildRichIntent(Activity activity, EmailContent content, String body) {
        Intent rich = new Intent(Intent.ACTION_SEND);
        // Broad type so the sheet lists email and every messaging app; each keeps image + text.
        // This is NOT a Slack workaround — Slack is handled separately in addSlackTextOnlyOverride.
        rich.setType("*/*");
        rich.putExtra(Intent.EXTRA_TEXT, body);
        rich.putExtra(Intent.EXTRA_SUBJECT, content.subject());
        rich.putExtra(Intent.EXTRA_TITLE, content.subject());   // sharesheet preview title
        if (content.recipient() != null) {
            rich.putExtra(Intent.EXTRA_EMAIL, new String[]{content.recipient()});
        }
        if (content.attachmentPath() != null) {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".provider", new File(content.attachmentPath()));
            rich.putExtra(Intent.EXTRA_STREAM, uri);
            // ClipData carries the read grant to whichever app is chosen.
            rich.setClipData(ClipData.newUri(activity.getContentResolver(), "screenshot", uri));
            rich.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return rich;
    }

    /**
     * If Slack is installed, exclude its rich entry from the sheet and add a text-only one in its
     * place, so Slack keeps the report text. targetSdk is 28, so package-visibility filtering (API
     * 30+) does not apply and {@code queryIntentActivities} sees Slack directly — no {@code <queries>}
     * declaration is needed.
     */
    private static void addSlackTextOnlyOverride(Activity activity, Intent rich, Intent chooser,
                                                 String body, String subject) {
        PackageManager pm = activity.getPackageManager();
        ResolveInfo slack = null;
        List<ResolveInfo> handlers = pm.queryIntentActivities(rich, 0);
        for (ResolveInfo ri : handlers) {
            if (ri.activityInfo != null && ri.activityInfo.packageName != null
                    && ri.activityInfo.packageName.toLowerCase(Locale.US).contains(SLACK_HINT)) {
                slack = ri;
                break;
            }
        }
        if (slack == null) {
            return;   // Slack not installed / not found -> rich intent + clipboard fallback stand
        }
        ComponentName comp = new ComponentName(slack.activityInfo.packageName, slack.activityInfo.name);

        Intent textOnly = new Intent(Intent.ACTION_SEND);
        textOnly.setType("text/plain");   // no stream -> Slack keeps the full text
        textOnly.putExtra(Intent.EXTRA_TEXT, body);
        textOnly.putExtra(Intent.EXTRA_SUBJECT, subject);
        textOnly.setComponent(comp);

        LabeledIntent labeled = new LabeledIntent(textOnly, slack.activityInfo.packageName,
                slack.loadLabel(pm).toString(), slack.getIconResource());

        // Exclude Slack's rich entry (this also drops its Direct Share targets — accepted, see the
        // class doc) and add the text-only one in its place, so Slack keeps the text.
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{comp});
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{labeled});
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
