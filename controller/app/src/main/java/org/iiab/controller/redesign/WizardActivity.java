package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.iiab.controller.R;
import org.iiab.controller.applang.data.AppLocaleController;
import org.iiab.controller.applang.data.ContentLanguage;
import org.iiab.controller.applang.domain.AppLanguage;
import org.iiab.controller.applang.domain.SupportedAppLanguages;
import org.iiab.controller.delivery.data.AnalyticsConsent;
import java.util.Locale;

/**
 * New first-run onboarding (ADFA-4725): Welcome -> Language -> Permissions -> Set up your library.
 * Replaces the legacy SetupActivity in the redesign flow (M3 theme). Notifications / Storage /
 * Keep-running only (overlay dropped). Set up your library -> SetupLibraryActivity (Step 1/2).
 */
public class WizardActivity extends AppCompatActivity {

    private int step = 0;
    private String langTag = ""; // "" = system

    private TextView title, subtitle, primary;
    private View welcome, language, perms, setup, back;
    private TextView notifStatus, storageStatus, batteryStatus;
    private TextView langBoxLabel, langHelper;

    private final ActivityResultLauncher<Intent> permResult =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> render());

    private final ActivityResultLauncher<Intent> langPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    langTag = r.getData().getStringExtra(WizardLanguagePickerActivity.EXTRA_TAG);
                    if (langTag == null) langTag = "";
                    updateLangBox();
                }
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_k2go_wizard);
        // ADFA-4797: survive the locale-change recreate — keep the step and re-read the
        // applied language, so we don't flash back to the welcome step.
        langTag = AppLocaleController.currentTag();
        if (b != null) step = b.getInt("step", 0);
        title = findViewById(R.id.wiz_title);
        subtitle = findViewById(R.id.wiz_subtitle);
        primary = findViewById(R.id.wiz_primary);
        welcome = findViewById(R.id.wiz_welcome);
        language = findViewById(R.id.wiz_language);
        perms = findViewById(R.id.wiz_perms);
        setup = findViewById(R.id.wiz_setup);
        back = findViewById(R.id.wiz_back);
        notifStatus = findViewById(R.id.perm_notif_status);
        storageStatus = findViewById(R.id.perm_storage_status);
        batteryStatus = findViewById(R.id.perm_battery_status);

        // language selection (ADFA-4797): tap the box -> full "Choose language" picker
        langBoxLabel = findViewById(R.id.lang_box_label);
        langHelper = findViewById(R.id.lang_helper);
        findViewById(R.id.lang_box).setOnClickListener(v -> langPicker.launch(
                new Intent(this, WizardLanguagePickerActivity.class)
                        .putExtra(WizardLanguagePickerActivity.EXTRA_TAG, langTag)));
        updateLangBox();

        // permission requests
        findViewById(R.id.perm_notif).setOnClickListener(v -> requestNotif());
        findViewById(R.id.perm_storage).setOnClickListener(v -> requestStorage());
        findViewById(R.id.perm_battery).setOnClickListener(v -> requestBattery());

        // set-up-library choices
        findViewById(R.id.setup_download).setOnClickListener(v -> {
            markComplete();
            startActivity(new Intent(this, SetupLibraryActivity.class));
            finish();
        });
        findViewById(R.id.setup_copy).setOnClickListener(v -> {
            markComplete();
            // ADFA-4777: "Copy from a phone" lands directly on the (now functional) Clone tab.
            startActivity(new Intent(this, LibraryActivity.class)
                    .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_clone));
            finish();
        });
        findViewById(R.id.wiz_skip_for_now).setOnClickListener(v -> {
            markComplete();
            startActivity(new Intent(this, LibraryActivity.class));
            finish();
        });

        primary.setOnClickListener(v -> onPrimary());
        back.setOnClickListener(v -> goBack());
        render();

        // ADFA-4797: on a recreate (e.g. right after a language change) fade the screen in
        // instead of a hard flash; first launch (b == null) stays instant.
        if (b != null) {
            View content = findViewById(android.R.id.content);
            content.setAlpha(0f);
            content.animate().alpha(1f).setDuration(220).start();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
    }

    private void updateLangBox() {
        String label = getString(R.string.k2go_lang_follow_system);
        for (AppLanguage l : SupportedAppLanguages.forPicker(label)) {
            if (l.tag().equals(langTag)) { label = l.toString(); break; }
        }
        langBoxLabel.setText(label);
        if (langTag.isEmpty()) {
            String sys = Locale.getDefault().getDisplayLanguage(Locale.getDefault());
            if (!sys.isEmpty()) sys = sys.substring(0, 1).toUpperCase(Locale.getDefault()) + sys.substring(1);
            langHelper.setText(getString(R.string.k2go_lang_helper_system, sys));
        } else {
            langHelper.setText(getString(R.string.k2go_lang_helper_specific, label));
        }
    }

    private void onPrimary() {
        if (step == 0) {
            step = 1;
            render();
        } else if (step == 1) {
            step = 2;               // set before apply so it survives the recreate
            applyLanguage();
            render();
        } else if (step == 2) {
            // ADFA-4802: after permissions, ask once for anonymous usage-stats consent
            // (mirrors the legacy flow), then advance to "Set up your library".
            if (allPermsGranted()) maybeAskAnalytics(() -> { step = 3; render(); });
        }
    }

    /** One-time usage-stats consent prompt; runs {@code onDone} after the choice (or immediately
     *  if already asked here or in the legacy flow). Reuses the analytics_enroll_* strings. */
    private void maybeAskAnalytics(Runnable onDone) {
        if (AnalyticsConsent.wasAsked(this)) { onDone.run(); return; }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.analytics_enroll_title)
                .setMessage(getString(R.string.analytics_enroll_body, getString(R.string.app_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.analytics_enroll_accept, (d, w) -> setAnalytics(true, onDone))
                .setNegativeButton(R.string.analytics_enroll_decline, (d, w) -> setAnalytics(false, onDone))
                .show();
    }

    private void setAnalytics(boolean on, Runnable onDone) {
        AnalyticsConsent.setEnabled(this, on);
        AnalyticsConsent.markAsked(this);
        org.iiab.controller.analytics.AnalyticsClient.with(this).applyConsent();
        onDone.run();
    }

    private void goBack() {
        if (step > 0) { step--; render(); }
    }

    @Override
    public void onBackPressed() {
        if (step > 0) { step--; render(); }
        else super.onBackPressed();
    }

    private void render() {
        welcome.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        language.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        perms.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        setup.setVisibility(step == 3 ? View.VISIBLE : View.GONE);
        title.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        subtitle.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        back.setVisibility(step >= 1 ? View.VISIBLE : View.GONE);

        switch (step) {
            case 0:
                primary.setVisibility(View.VISIBLE);
                primary.setText(getString(R.string.k2go_get_started));
                enable(primary, true);
                break;
            case 1:
                title.setText(getString(R.string.k2go_wiz_choose_language));
                subtitle.setText(getString(R.string.k2go_wiz_language_sub));
                primary.setVisibility(View.VISIBLE);
                primary.setText(getString(R.string.k2go_next));
                enable(primary, true);
                break;
            case 2:
                title.setText(getString(R.string.k2go_wiz_permissions_title));
                subtitle.setText("");
                refreshPermStatuses();
                primary.setVisibility(View.VISIBLE);
                primary.setText(getString(R.string.k2go_continue));
                enable(primary, allPermsGranted());
                break;
            default:
                title.setText(getString(R.string.k2go_setup_library_title));
                subtitle.setText(getString(R.string.k2go_wiz_setup_sub));
                primary.setVisibility(View.GONE);
                break;
        }
    }

    private void enable(TextView b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.5f);
    }

    // --- language ---
    private void applyLanguage() {
        // Content language = base subtag normalized to the Kiwix form (ru-RU -> ru); empty
        // tag (follow system) derives from the system locale. Mirrors SettingsSubFragment.
        String content = langTag.isEmpty()
                ? ContentLanguage.systemDefault()
                : ContentLanguage.normalize(langTag.split("-")[0]);
        prefs().edit().putString("selected_lang_minimal", content).apply();
        // Applying the UI locale recreates the activities (AppCompat persists the choice).
        AppLocaleController.apply(langTag);
    }

    // --- permissions ---
    private void refreshPermStatuses() {
        setStatus(notifStatus, hasNotif());
        setStatus(storageStatus, hasStorage());
        setStatus(batteryStatus, hasBattery());
    }
    private void setStatus(TextView t, boolean granted) {
        t.setText(getString(granted ? R.string.k2go_perm_granted : R.string.k2go_perm_allow));
        t.setTextColor(ContextCompat.getColor(this, granted ? R.color.k2go_leaf : R.color.k2go_teal));
    }
    private boolean allPermsGranted() {
        boolean ok = hasStorage() && hasBattery();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ok = ok && hasNotif();
        return ok;
    }
    private boolean hasNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return NotificationManagerCompat.from(this).areNotificationsEnabled();
        return true;
    }
    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return android.os.Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }
    private void requestNotif() {
        if (hasNotif()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            permResult.launch(i);
        }
    }
    private void requestStorage() {
        if (hasStorage()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.addCategory("android.intent.category.DEFAULT");
                i.setData(Uri.parse("package:" + getPackageName()));
                permResult.launch(i);
            } catch (Exception e) {
                permResult.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }
    private void requestBattery() {
        if (hasBattery()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            permResult.launch(i);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(getString(R.string.pref_file_internal), MODE_PRIVATE);
    }
    private void markComplete() {
        prefs().edit().putBoolean(getString(R.string.pref_key_setup_complete), true).apply();
    }
}
