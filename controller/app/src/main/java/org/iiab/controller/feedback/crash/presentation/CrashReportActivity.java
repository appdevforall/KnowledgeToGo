package org.iiab.controller.feedback.crash.presentation;

import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.iiab.controller.R;
import org.iiab.controller.deviceinfo.data.BuildDeviceAbiProvider;
import org.iiab.controller.deviceinfo.domain.GetDeviceArchUseCase;
import org.iiab.controller.feedback.crash.data.CrashReportStore;
import org.iiab.controller.feedback.data.EmailFeedbackSender;
import org.iiab.controller.feedback.data.FeedbackDiagnostics;
import org.iiab.controller.feedback.domain.EmailContent;
import org.iiab.controller.feedback.domain.FeedbackPayload;
import org.iiab.controller.feedback.domain.FeedbackRenderer;
import org.iiab.controller.feedback.domain.FeedbackType;

/**
 * Shown on the next launch after an uncaught crash: previews exactly what would be sent
 * (subject + body incl. the stack trace) and lets the operator Send (explicit tap, reusing
 * the email transport) or Dismiss. Either choice clears the stored crash.
 */
public class CrashReportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_report);

        CrashReportStore store = new CrashReportStore(this);
        String trace = store.readPending();
        if (trace == null) {
            finish();
            return;
        }

        EmailContent email = render(trace);
        TextView preview = findViewById(R.id.crash_preview);
        preview.setText(email.subject() + "\n\n" + email.body());

        findViewById(R.id.crash_dismiss).setOnClickListener(v -> {
            store.clear();
            finish();
        });
        findViewById(R.id.crash_send).setOnClickListener(v -> {
            boolean ok = new EmailFeedbackSender().send(this, email);
            if (ok) {
                store.clear();
                finish();
            } else {
                Toast.makeText(this, R.string.feedback_no_email_app, Toast.LENGTH_LONG).show();
            }
        });
    }

    private EmailContent render(String trace) {
        FeedbackPayload payload = FeedbackPayload.builder(FeedbackType.CRASH)
                .stackTrace(trace)
                .appVersion(FeedbackDiagnostics.appVersionName(this))
                .appBuild(FeedbackDiagnostics.appVersionCode(this))
                .androidRelease(Build.VERSION.RELEASE)
                .device(Build.MANUFACTURER + " " + Build.MODEL)
                .abi(new GetDeviceArchUseCase(new BuildDeviceAbiProvider()).execute())
                .binariesTag(FeedbackDiagnostics.binariesTag(this))
                .build();
        return new FeedbackRenderer().render(payload);
    }
}
