package org.iiab.controller.redesign;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.iiab.controller.R;
import org.iiab.controller.ServerController;
import org.iiab.controller.ServerStateRepository;
import org.iiab.controller.WatchdogService;
import org.iiab.controller.install.presentation.InstallProgressRepository;
import org.iiab.controller.install.presentation.InstallState;

/**
 * New content-first UI shell (ADFA-4725). Owns the server lifecycle for the new UI:
 * starts the status poll, auto-starts the stack if it is down, and shows a boot gate
 * (Lottie) that flips to OPEN once the server is reachable.
 * Phase 2 = runtime gate. Content cards, wizard and Step-2 land in later phases.
 */
public class LibraryActivity extends AppCompatActivity implements ServerController.Host {

    private static final String TAG = "K2Go-Library";
    private static final long AUTOSTART_DELAY_MS = 3500L;
    private static final long GATE_SAFETY_MS = 25000L;
    /** Nothing installed → nothing to boot: dismiss the gate promptly instead of waiting. */
    private static final long NO_SYSTEM_GATE_MS = 900L;
    /** Set by the Setup "Download" so the gate waits for the install to finish, not a timeout. */
    public static final String EXTRA_INSTALLING = "installing";
    /** ADFA-4777: preselect a bottom-nav tab on launch (e.g. from the wizard's "Copy from a phone"). */
    public static final String EXTRA_TAB = "tab";
    private boolean installing = false;

    private ServerController serverController;
    private boolean isNegotiating = false;
    private Boolean targetServerState = null;

    private LottieAnimationView bootGate;
    private View installProgress;
    private android.widget.TextView installStatus, installDetail;
    private android.widget.ProgressBar installBar;
    private boolean gateDismissed = false;
    private boolean closing = false;
    private boolean closedDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Not set up yet? Run the first-run wizard, then it routes back here.
        SharedPreferences prefs0 = getSharedPreferences(
                getString(R.string.pref_file_internal), MODE_PRIVATE);
        if (!prefs0.getBoolean(getString(R.string.pref_key_setup_complete), false)) {
            startActivity(new Intent(this, WizardActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_library);

        BottomNavigationView nav = findViewById(R.id.k2go_bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            nav.setSelectedItemId(getIntent().getIntExtra(EXTRA_TAB, R.id.nav_library));   // ADFA-4777
        }

        bootGate = findViewById(R.id.k2go_boot_gate);
        installProgress = findViewById(R.id.k2go_install_progress);
        installStatus = findViewById(R.id.k2go_install_status);
        installBar = findViewById(R.id.k2go_install_bar);
        installDetail = findViewById(R.id.k2go_install_detail);
        installing = getIntent().getBooleanExtra(EXTRA_INSTALLING, false);
        // The Lottie has a text layer (OPEN/CLOSED sign). Use the system typeface (Noto-based,
        // global script fallback) so localized words render in any language; a TextDelegate maps
        // the OPEN/CLOSED source text to the localized @string values.
        bootGate.setFontAssetDelegate(new com.airbnb.lottie.FontAssetDelegate() {
            @Override
            public android.graphics.Typeface fetchFont(String fontFamily) {
                return android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            }
        });
        com.airbnb.lottie.TextDelegate signText = new com.airbnb.lottie.TextDelegate(bootGate);
        signText.setText("OPEN", getString(R.string.k2go_sign_open));
        signText.setText("CLOSED", getString(R.string.k2go_sign_closed));
        bootGate.setTextDelegate(signText);
        if (!reduceMotion()) {
            bootGate.setAnimation(R.raw.library_animation);
            bootGate.setMinAndMaxFrame("A_ENTRY_LOOP");
            bootGate.setRepeatCount(LottieDrawable.INFINITE);
            bootGate.playAnimation();
        }

        // If the user skipped install there is no rootfs/server to wait for; the gate would
        // otherwise burn the full safety timeout. Detect it and dismiss quickly.
        final boolean systemInstalled = org.iiab.controller.SystemStateEvaluator.isSystemInstalled(this);

        serverController = new ServerController(this, this);
        serverController.start();

        ServerStateRepository.get().state().observe(this, s -> {
            if (s == null) return;
            if (closing) {
                if (!s.alive) onClosedReady();
            } else if (s.alive) {
                onServerReady();
            }
        });

        // Keep the gate up while an install runs, showing real progress, and dismiss only
        // when it actually finishes (or fails) — a 2-3 GB download won't beat a timeout.
        InstallProgressRepository.get().state().observe(this, st -> {
            if (st == null || gateDismissed) return;
            if (st.isRunning()) { installing = true; showInstallProgress(st); }
            else if (st.isTerminal()) { hideInstallProgress(); onServerReady(); }
        });

        Handler main = new Handler(Looper.getMainLooper());
        if (installing) {
            // A download is in progress: keep the gate and show live progress; dismissal
            // comes from the install reaching SUCCESS/FAILED, not a timeout.
            showInstallProgress(InstallProgressRepository.get().current());
        } else {
            // If the stack isn't up after one poll cycle, start it.
            if (systemInstalled) {
                main.postDelayed(() -> {
                    if (!isFinishing()
                            && !ServerStateRepository.get().current().alive
                            && targetServerState == null) {
                        serverController.handleServerLaunchClick(findViewById(android.R.id.content));
                    }
                }, AUTOSTART_DELAY_MS);
            }
            // Safety: never trap the user behind the gate.
            main.postDelayed(() -> {
                if (!gateDismissed) {
                    onServerReady();
                }
            }, systemInstalled ? GATE_SAFETY_MS : NO_SYSTEM_GATE_MS);
        }
    }

    private void showInstallProgress(InstallState st) {
        if (installProgress == null || st == null || !st.isRunning()) return;
        installProgress.setVisibility(View.VISIBLE);
        if (st.phase == InstallState.Phase.DOWNLOADING) {
            installStatus.setText(getString(R.string.k2go_downloading_library));
            installBar.setIndeterminate(false);
            installBar.setProgress(st.percent);
            installDetail.setText(st.percent + "%" + (st.speed.isEmpty() ? "" : "  ·  " + st.speed));
        } else {
            installStatus.setText(st.message.isEmpty() ? getString(R.string.k2go_setting_up_library) : st.message);
            installBar.setIndeterminate(true);
            installDetail.setText("");
        }
    }

    private void hideInstallProgress() {
        if (installProgress != null) installProgress.setVisibility(View.GONE);
    }

    private void onServerReady() {
        if (gateDismissed || bootGate == null) {
            return;
        }
        gateDismissed = true;
        hideInstallProgress();
        if (reduceMotion()) { bootGate.setVisibility(View.GONE); return; }
        bootGate.removeAllAnimatorListeners();
        bootGate.setRepeatCount(0);
        bootGate.setMinAndMaxFrame("B_OPEN_FLIP");
        bootGate.addAnimatorListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (bootGate != null) {
                    bootGate.setVisibility(View.GONE);
                }
            }
        });
        bootGate.playAnimation();
    }

    private void showTab(int itemId) {
        final String title;
        if (itemId == R.id.nav_connect) {
            title = "Connect";
        } else if (itemId == R.id.nav_clone) {
            title = "Clone";
        } else if (itemId == R.id.nav_settings) {
            title = "Settings";
        } else {
            title = "Library";
        }
        androidx.fragment.app.Fragment f;
        if (itemId == R.id.nav_library) {
            f = new LibraryHomeFragment();
        } else if (itemId == R.id.nav_connect) {
            f = new ConnectFragment();
        } else if (itemId == R.id.nav_clone) {
            f = new CloneFragment();
        } else if (itemId == R.id.nav_settings) {
            f = new SettingsFragment();
        } else {
            f = PlaceholderFragment.newInstance(title);
        }
        // Switching tabs clears any Settings sub-screen on the back stack.
        getSupportFragmentManager().popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_nav_host, f)
                .commit();
    }

    /** Push a Settings sub-screen (Language/About/Advanced/Feedback) keeping the bottom nav. */
    public void openSettingsSub(androidx.fragment.app.Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_nav_host, f)
                .addToBackStack("settings_sub")
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (serverController != null) serverController.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (serverController != null) serverController.onPause();
    }

    private boolean reduceMotion() {
        try {
            return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /** Settings "Turn off K2Go": full-screen closing scene + graceful teardown, then leave. */
    public void turnOffK2Go() {
        if (closing) return;
        closing = true;
        if (bootGate != null && !reduceMotion()) {
            bootGate.setVisibility(View.VISIBLE);
            bootGate.removeAllAnimatorListeners();
            bootGate.setRepeatCount(LottieDrawable.INFINITE);
            bootGate.setMinAndMaxFrame("C_EXIT_LOOP");
            bootGate.playAnimation();
        }
        if (ServerStateRepository.get().current().alive && targetServerState == null) {
            serverController.handleServerLaunchClick(findViewById(android.R.id.content));
        } else if (!ServerStateRepository.get().current().alive) {
            onClosedReady();
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::onClosedReady, 15000L);
    }

    private void onClosedReady() {
        if (closedDone) return;
        closedDone = true;
        if (bootGate == null) { finishAndRemoveTask(); return; }
        if (reduceMotion()) { finishAndRemoveTask(); return; }
        bootGate.removeAllAnimatorListeners();
        bootGate.setRepeatCount(0);
        bootGate.setMinAndMaxFrame("D_CLOSED_FLIP");
        bootGate.addAnimatorListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) { finishAndRemoveTask(); }
        });
        bootGate.playAnimation();
    }

    // --- ServerController.Host (shell: pulses / LEDs are no-ops for now) --------
    @Override public void addToLog(String message) { Log.d(TAG, message); }
    @Override public void startFusionPulse() { }
    @Override public void startExitPulse() { }
    @Override public void stopBtnProgress() { }
    @Override public void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn) { }
    @Override public void refreshServerUi() { }
    @Override public Boolean getTargetServerState() { return targetServerState; }
    @Override public void setTargetServerState(Boolean target) { targetServerState = target; }
    @Override public boolean isNegotiating() { return isNegotiating; }

    @Override
    public void enableSystemProtection() {
        Intent i = new Intent(this, WatchdogService.class);
        i.setAction(WatchdogService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    @Override
    public void disableSystemProtection() {
        Intent i = new Intent(this, WatchdogService.class);
        i.setAction(WatchdogService.ACTION_STOP);
        startService(i);
    }
}
