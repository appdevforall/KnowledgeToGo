package org.iiab.controller.settings;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.feedback.presentation.FeedbackFragment;

/**
 * Settings shell: a compact hand-rolled vertical rail (Setup / Feedback / About) that
 * swaps section fragments into a content container. Thin host — it only navigates, so no
 * section logic lives here (avoids a god class). The first-run wizard {@code SetupActivity}
 * is intentionally left untouched.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.nav_setup).setOnClickListener(v -> show(new SettingsSetupFragment()));
        findViewById(R.id.nav_feedback).setOnClickListener(v -> show(new FeedbackFragment()));
        findViewById(R.id.nav_about).setOnClickListener(v -> show(new AboutFragment()));

        if (savedInstanceState == null) {
            show(new SettingsSetupFragment());
        }
    }

    private void show(@NonNull Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_content, fragment)
                .commit();
    }
}
