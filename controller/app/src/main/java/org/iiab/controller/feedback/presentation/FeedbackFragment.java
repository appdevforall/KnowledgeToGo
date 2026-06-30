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

import org.iiab.controller.R;
import org.iiab.controller.deviceinfo.data.BuildDeviceAbiProvider;
import org.iiab.controller.deviceinfo.domain.GetDeviceArchUseCase;
import org.iiab.controller.feedback.data.EmailFeedbackSender;
import org.iiab.controller.feedback.data.FeedbackDiagnostics;
import org.iiab.controller.feedback.domain.EmailContent;
import org.iiab.controller.feedback.domain.FeedbackPayload;
import org.iiab.controller.feedback.domain.FeedbackRenderer;
import org.iiab.controller.feedback.domain.FeedbackType;

/**
 * "Send feedback" form (operator v1): category + message + optional contact email.
 * Builds a {@link FeedbackPayload}, renders it with {@link FeedbackRenderer}, and sends
 * it via {@link EmailFeedbackSender} (mailto). The migration to the Cloudflare Worker
 * swaps only the sender; this form and the payload stay the same.
 */
public class FeedbackFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_feedback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Spinner category = v.findViewById(R.id.feedback_category);
        EditText message = v.findViewById(R.id.feedback_message);
        EditText email = v.findViewById(R.id.feedback_email);
        Button send = v.findViewById(R.id.feedback_send);

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
                    .build();
            EmailContent content = new FeedbackRenderer().render(payload);
            boolean ok = new EmailFeedbackSender().send(requireContext(), content);
            if (!ok) {
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
