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
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import org.iiab.controller.R;
import org.iiab.controller.applang.data.AppLocaleController;
import org.iiab.controller.applang.data.ContentLanguage;

/**
 * New first-run onboarding (ADFA-4725): Welcome -> Language -> Permissions -> Set up your library.
 * Replaces the legacy SetupActivity in the redesign flow (M3 theme). Notifications / Storage /
 * Keep-running only (overlay dropped). Set up your library -> SetupLibraryActivity (Step 1/2).
 */
public class WizardActivity extends AppCompatActivity {

    private int step = 0;
    private String langTag = ""; // "" = system

    private TextView title, subtitle, primary;
    private View welcome, language, perms, setup;
    private TextView notifStatus, storageStatus, batteryStatus;

    private final ActivityResultLauncher<Intent> permResult =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> render());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_k2go_wizard);
        title = findViewById(R.id.wiz_title);
        subtitle = findViewById(R.id.wiz_subtitle);
        primary = findViewById(R.id.wiz_primary);
        welcome = findViewById(R.id.wiz_welcome);
        language = findViewById(R.id.wiz_language);
        perms = findViewById(R.id.wiz_perms);
        setup = findViewById(R.id.wiz_setup);
        notifStatus = findViewById(R.id.perm_notif_status);
        storageStatus = findViewById(R.id.perm_storage_status);
        batteryStatus = findViewById(R.id.perm_battery_status);

        // language selection
        findViewById(R.id.lang_system).setOnClickListener(v -> pickLang(""));
        findViewById(R.id.lang_en).setOnClickListener(v -> pickLang("en"));
        findViewById(R.id.lang_es).setOnClickListener(v -> pickLang("es"));

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
            startActivity(new Intent(this, LibraryActivity.class));
            finish();
        });

        primary.setOnClickListener(v -> onPrimary());
        render();
    }

    private void pickLang(String tag) {
        langTag = tag;
        ((ImageView) findViewById(R.id.lang_system_radio)).setImageResource(tag.isEmpty() ? R.drawable.ic_radio_on : R.drawable.ic_radio_off);
        ((ImageView) findViewById(R.id.lang_en_radio)).setImageResource("en".equals(tag) ? R.drawable.ic_radio_on : R.drawable.ic_radio_off);
        ((ImageView) findViewById(R.id.lang_es_radio)).setImageResource("es".equals(tag) ? R.drawable.ic_radio_on : R.drawable.ic_radio_off);
    }

    private void onPrimary() {
        if (step == 0) {
            step = 1;
        } else if (step == 1) {
            applyLanguage();
            step = 2;
        } else if (step == 2) {
            if (allPermsGranted()) step = 3;
        }
        render();
    }

    private void render() {
        welcome.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        language.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        perms.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        setup.setVisibility(step == 3 ? View.VISIBLE : View.GONE);

        switch (step) {
            case 0:
                title.setText("Knowledge To Go");
                subtitle.setText("A library in your pocket");
                primary.setVisibility(View.VISIBLE);
                primary.setText("Get started");
                enable(primary, true);
                break;
            case 1:
                title.setText("Choose language");
                subtitle.setText("Sets app + content language.");
                primary.setVisibility(View.VISIBLE);
                primary.setText("Next");
                enable(primary, true);
                break;
            case 2:
                title.setText("Allow a few things");
                subtitle.setText("");
                refreshPermStatuses();
                primary.setVisibility(View.VISIBLE);
                primary.setText("Continue");
                enable(primary, allPermsGranted());
                break;
            default:
                title.setText("Set up your library");
                subtitle.setText("How do you want it?");
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
        AppLocaleController.apply(langTag);
        String content = langTag.isEmpty() ? ContentLanguage.systemDefault() : langTag;
        prefs().edit().putString("selected_lang_minimal", content).apply();
    }

    // --- permissions ---
    private void refreshPermStatuses() {
        setStatus(notifStatus, hasNotif());
        setStatus(storageStatus, hasStorage());
        setStatus(batteryStatus, hasBattery());
    }
    private void setStatus(TextView t, boolean granted) {
        t.setText(granted ? "Granted" : "Allow");
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
