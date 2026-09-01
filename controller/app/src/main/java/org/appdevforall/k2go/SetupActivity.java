/*
 * ============================================================================
 * Name        : SetupActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Host for the setup/settings shell (WIZARD and SETTINGS modes)
 * ============================================================================
 */
package org.appdevforall.k2go;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import org.appdevforall.k2go.util.Snackbars;

import org.appdevforall.k2go.feedback.presentation.FeedbackFragment;
import org.appdevforall.k2go.settings.AboutFragment;

/**
 * Host for the setup/settings shell. First run (setup not complete) opens in WIZARD
 * mode: only the Setup section, Back blocked. After setup it opens (from the header
 * gear) in SETTINGS mode: a compact rail switches between Setup, Feedback and About.
 * Thin host — section logic lives in the fragments (no god class).
 */
public class SetupActivity extends AppCompatActivity {

    /**
     * ADFA-5137: true opens this screen as the first-run wizard, false as Settings. Set by whoever
     * opens it, because the mode is a property of the reason for opening it and of nothing else.
     * Absent means Settings, which is the safe default: a Settings screen is navigable and a wizard
     * blocks Back.
     */
    public static final String EXTRA_WIZARD_MODE = "org.iiab.controller.SETUP_WIZARD_MODE";

    private boolean wizardMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        org.appdevforall.k2go.help.TooltipWiring.wireAll(getWindow().getDecorView());

        // ADFA-5137: the caller says which mode this is, because only the caller knows.
        //
        // It used to read setup_complete, and that was the one reader asking a genuinely different
        // question: not "is there a system" but "am I the first-run wizard or am I Settings". Two
        // callers open this screen for those two reasons — MainActivity's first-run redirect and its
        // Settings button — so the answer belongs in the Intent. Migrating this one to the presence
        // rule would have produced a Settings screen that believes it is a wizard.
        wizardMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_WIZARD_MODE, false);

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
            show(SetupSectionFragment.newInstance(wizardMode));
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
