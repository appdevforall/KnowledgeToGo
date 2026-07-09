/*
 * ============================================================================
 * Name        : SetupActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Host for the setup/settings shell (WIZARD and SETTINGS modes)
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import org.iiab.controller.util.Snackbars;

import org.iiab.controller.feedback.presentation.FeedbackFragment;
import org.iiab.controller.settings.AboutFragment;

/**
 * Host for the setup/settings shell. First run (setup not complete) opens in WIZARD
 * mode: only the Setup section, Back blocked. After setup it opens (from the header
 * gear) in SETTINGS mode: a compact rail switches between Setup, Feedback and About.
 * Thin host — section logic lives in the fragments (no god class).
 */
public class SetupActivity extends AppCompatActivity {

    private boolean wizardMode;

    // ADFA-4538: launch straight into the feedback form (from the MainActivity FAB).
    public static final String EXTRA_OPEN_FEEDBACK = "open_feedback";
    public static final String EXTRA_SCREENSHOT_PATH = "screenshot_path";
    public static final String EXTRA_SCREEN = "screen";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        org.iiab.controller.help.TooltipWiring.wireAll(getWindow().getDecorView());

        SharedPreferences prefs = getSharedPreferences(
                getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        wizardMode = !prefs.getBoolean(getString(R.string.pref_key_setup_complete), false);

        View rail = findViewById(R.id.setup_rail);
        if (wizardMode) {
            rail.setVisibility(View.GONE);
        } else {
            rail.setVisibility(View.VISIBLE);
            findViewById(R.id.settings_topbar).setVisibility(View.VISIBLE);
            findViewById(R.id.btn_settings_done).setOnClickListener(v -> finish());
            findViewById(R.id.nav_setup).setOnClickListener(v -> show(SetupSectionFragment.newInstance(false)));
            findViewById(R.id.nav_feedback).setOnClickListener(v -> show(new FeedbackFragment()));
            findViewById(R.id.nav_about).setOnClickListener(v -> show(new AboutFragment()));
        }

        if (savedInstanceState == null) {
            if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_FEEDBACK, false)) {
                show(FeedbackFragment.newInstance(
                        getIntent().getStringExtra(EXTRA_SCREENSHOT_PATH),
                        getIntent().getStringExtra(EXTRA_SCREEN)));
            } else {
                show(SetupSectionFragment.newInstance(wizardMode));
            }
        }
    }

    private void show(@NonNull Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.setup_content, fragment)
                .commit();
    }

    @Override
    public void onBackPressed() {
        if (wizardMode) {
            Snackbars.make(findViewById(android.R.id.content),
                    getString(R.string.setup_back_blocked_msg)).show();
        } else {
            super.onBackPressed();
        }
    }
}
