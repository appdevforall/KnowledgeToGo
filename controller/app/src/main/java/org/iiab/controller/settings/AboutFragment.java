package org.iiab.controller.settings;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.delivery.data.AnalyticsConsent;

/** About section: app name, version, the App Dev for All credit, and the anonymous
 * usage-statistics opt-in toggle (mirrors the first-run enrollment choice). */
public class AboutFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        org.iiab.controller.help.TooltipWiring.wireAll(view);
        TextView versionView = view.findViewById(R.id.about_version);
        versionView.setText(getString(R.string.about_version, appVersionName()));

        SwitchCompat analytics = view.findViewById(R.id.switch_analytics_consent);
        analytics.setChecked(AnalyticsConsent.isEnabled(requireContext()));
        analytics.setOnCheckedChangeListener(
                (btn, checked) -> {
                    AnalyticsConsent.setEnabled(requireContext(), checked);
                    org.iiab.controller.analytics.AnalyticsClient.with(requireContext()).applyConsent();
                });
    }

    private String appVersionName() {
        try {
            PackageManager pm = requireContext().getPackageManager();
            return pm.getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
