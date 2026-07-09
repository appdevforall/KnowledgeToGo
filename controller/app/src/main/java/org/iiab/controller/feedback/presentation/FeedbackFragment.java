package org.iiab.controller.feedback.presentation;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.iiab.controller.BuildConfig;
import org.iiab.controller.R;
import org.iiab.controller.deviceinfo.data.BuildDeviceAbiProvider;
import org.iiab.controller.deviceinfo.domain.GetDeviceArchUseCase;
import org.iiab.controller.feedback.data.FeedbackConfig;
import org.iiab.controller.feedback.data.FeedbackTransport;
import org.iiab.controller.feedback.data.FeedbackDiagnostics;
import org.iiab.controller.feedback.domain.FeedbackPayload;
import org.iiab.controller.feedback.domain.FeedbackType;

/**
 * "Send feedback" form (operator v1): category + message + optional contact email.
 * Builds a {@link FeedbackPayload} and sends it via the {@link FeedbackTransport} chosen
 * by {@link FeedbackConfig} (mailto by default; the Cloudflare Worker behind a flag).
 * This form and the payload stay the same regardless of transport.
 */
public class FeedbackFragment extends Fragment {

    private static final String ARG_SCREEN = "screen";
    private static final String ARG_SHOT = "screenshot_path";

    /** Opens the form pre-attaching an optional screenshot and tagging the origin screen. */
    public static FeedbackFragment newInstance(String screenshotPath, String screen) {
        FeedbackFragment f = new FeedbackFragment();
        Bundle b = new Bundle();
        if (screenshotPath != null) {
            b.putString(ARG_SHOT, screenshotPath);
        }
        if (screen != null) {
            b.putString(ARG_SCREEN, screen);
        }
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_feedback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        org.iiab.controller.help.TooltipWiring.wireAll(v);

        Bundle args = getArguments();
        final String screenArg = args != null ? args.getString(ARG_SCREEN) : null;
        final String shotArg = args != null ? args.getString(ARG_SHOT) : null;

        Spinner category = v.findViewById(R.id.feedback_category);
        EditText message = v.findViewById(R.id.feedback_message);
        EditText email = v.findViewById(R.id.feedback_email);
        Button send = v.findViewById(R.id.feedback_send);

        Button forceCrash = v.findViewById(R.id.feedback_force_crash);
        if (BuildConfig.DEBUG) {
            forceCrash.setVisibility(View.VISIBLE);
            forceCrash.setOnClickListener(x -> {
                throw new RuntimeException("K2Go test crash");
            });
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.feedback_cat_bug),
                getString(R.string.feedback_cat_suggestion),
                getString(R.string.feedback_cat_content)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(adapter);

        send.setOnClickListener(view -> {
            String msg = message.getText().toString().trim();
            if (msg.isEmpty()) {
                Toast.makeText(requireContext(), R.string.feedback_empty_message, Toast.LENGTH_SHORT).show();
                return;
            }
            String contact = email.getText().toString().trim();
            FeedbackPayload payload = FeedbackPayload.builder(typeFor(category.getSelectedItemPosition()))
                    .message(msg)
                    .contactEmail(contact.isEmpty() ? null : contact)
                    .appVersion(FeedbackDiagnostics.appVersionName(requireContext()))
                    .appBuild(FeedbackDiagnostics.appVersionCode(requireContext()))
                    .androidRelease(Build.VERSION.RELEASE)
                    .device(Build.MANUFACTURER + " " + Build.MODEL)
                    .abi(new GetDeviceArchUseCase(new BuildDeviceAbiProvider()).execute())
                    .binariesTag(FeedbackDiagnostics.binariesTag(requireContext()))
                    .screen(screenArg)
                    .screenshot(shotArg)
                    .build();
            FeedbackTransport transport = FeedbackConfig.create(requireContext());
            boolean ok = transport.send(requireContext(), payload);
            if (ok) {
                // ADFA-4466 Phase 2: feedback-channel adoption (no content; no-op unless opted in).
                org.iiab.controller.analytics.AnalyticsClient.with(requireContext()).logFeedbackSent();
            } else {
                Toast.makeText(requireContext(), R.string.feedback_no_email_app, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static FeedbackType typeFor(int position) {
        switch (position) {
            case 1:
                return FeedbackType.SUGGESTION;
            case 2:
                return FeedbackType.CONTENT_REQUEST;
            default:
                return FeedbackType.BUG;
        }
    }
}
