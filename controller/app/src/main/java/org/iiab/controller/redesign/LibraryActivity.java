package org.iiab.controller.redesign;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.google.android.material.navigation.NavigationBarView;
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

    /** ADFA-4799: bottom bar (compact) and rail (medium/expanded) share the NavigationBarView
     *  API and the same menu; we just toggle which one is visible by window width. */
    private static final int MEDIUM_MIN_DP = 600;
    private static final String STATE_TAB = "k2go_tab";
    private NavigationBarView bottomNav, railNav;
    private int currentTab = R.id.nav_library;
    private boolean navSyncing = false;

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
    private boolean moduleBusy = false;   // ADFA-4842: a module (runrole) queue is running

    // ADFA-4837: animated "…" on the boot status line so the long pre-pdsm silence doesn't look frozen.
    private final Handler ellipsisHandler = new Handler(Looper.getMainLooper());
    private Runnable ellipsisRunnable;
    private String bootBaseText;

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

        bottomNav = findViewById(R.id.k2go_bottom_nav);
        railNav = findViewById(R.id.k2go_nav_rail);
        NavigationBarView.OnItemSelectedListener navListener = item -> {
            if (!navSyncing) {
                currentTab = item.getItemId();
                showTab(currentTab);
                syncSelection(currentTab);
            }
            return true;
        };
        bottomNav.setOnItemSelectedListener(navListener);
        railNav.setOnItemSelectedListener(navListener);
        applyNavForWidth();

        currentTab = (savedInstanceState != null)
                ? savedInstanceState.getInt(STATE_TAB, R.id.nav_library)
                : getIntent().getIntExtra(EXTRA_TAB, R.id.nav_library);   // ADFA-4777
        showTab(currentTab);
        syncSelection(currentTab);

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
            } else if (s.alive && !installing) {
                // ADFA-4811: don't lift the boot gate on a server the installer transiently brings
                // up mid-install; the gate is dismissed when the install reaches a terminal state.
                onServerReady();
            }
        });

        // Keep the gate up while an install runs, showing real progress, and dismiss only
        // when it actually finishes (or fails) — a 2-3 GB download won't beat a timeout.
        InstallProgressRepository.get().state().observe(this, st -> {
            if (st == null || gateDismissed) return;
            if (st.isRunning()) {
                installing = true;
                showInstallProgress(st);
            } else if (st.isTerminal()) {
                hideInstallProgress();
                installing = false; // install finished; let the server-alive observer lift the gate
                if (st.phase == InstallState.Phase.SUCCESS) {
                    // ADFA-4811: start the server in this same session so the library is usable on
                    // the FIRST run (no relaunch). The install just cleared the guard, so this is
                    // allowed. The gate stays until the server responds (alive observer), with a
                    // safety timeout so the user is never trapped if it doesn't come up.
                    if (!ServerStateRepository.get().current().alive && targetServerState == null) {
                        serverController.handleServerLaunchClick(findViewById(android.R.id.content));
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!gateDismissed) onServerReady();
                    }, GATE_SAFETY_MS);
                } else {
                    onServerReady(); // FAILED: lift the gate; land on the library (offline)
                }
            }
        });

        // ADFA-4842: while a module (runrole) queue runs, the server is stopped and the rootfs is
        // being modified — block the WHOLE library UI (every tab + nav) behind the full-screen boot
        // gate, show which module is installing, and bring the server back when the queue finishes.
        org.iiab.controller.install.presentation.ModuleQueueRepository.get().state().observe(this, ms -> {
            if (ms == null) return;
            if (ms.isRunning()) {
                if (!moduleBusy) { moduleBusy = true; showModuleBusy(); }
                if (installDetail != null) installDetail.setText(ms.currentModule == null ? "" : ms.currentModule);
            } else if (moduleBusy) {
                moduleBusy = false;
                hideModuleBusy();
                if (canStartServer()) startServer();   // the server was stopped for the runroles
            }
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
        stopBootEllipsis();   // ADFA-4837: an install owns the status line; stop the boot animation
        installProgress.setVisibility(View.VISIBLE);
        if (installBar != null) installBar.setVisibility(View.VISIBLE);   // ADFA-4837: boot/shutdown hide it
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
        stopBootEllipsis();   // ADFA-4837
        if (installProgress != null) installProgress.setVisibility(View.GONE);
    }

    // ADFA-4842: full-screen "system updating" block while a module (runrole) queue runs. The boot
    // gate is match_parent + clickable and drawn over the nav content, so it blocks EVERY tab
    // (Library, Connect, Clone, Settings) and the nav bar — not just the Library cards.
    private void showModuleBusy() {
        if (closing) return;
        stopBootEllipsis();
        if (installProgress != null) {
            installProgress.setVisibility(View.VISIBLE);
            if (installStatus != null) installStatus.setText(getString(R.string.install_busy_modules));
            if (installBar != null) { installBar.setVisibility(View.VISIBLE); installBar.setIndeterminate(true); }
        }
        if (bootGate != null) {
            bootGate.setVisibility(View.VISIBLE);
            if (!reduceMotion()) {
                bootGate.removeAllAnimatorListeners();
                bootGate.setRepeatCount(LottieDrawable.INFINITE);
                bootGate.setMinAndMaxFrame("A_ENTRY_LOOP");
                bootGate.playAnimation();
            }
        }
    }

    private void hideModuleBusy() {
        hideInstallProgress();
        if (bootGate != null) {
            bootGate.removeAllAnimatorListeners();
            bootGate.cancelAnimation();
            bootGate.setVisibility(View.GONE);
        }
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

    /** Show the rail in medium/expanded (>= 600dp wide), the bottom bar in compact. */
    private void applyNavForWidth() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= MEDIUM_MIN_DP;
        if (railNav != null) railNav.setVisibility(wide ? View.VISIBLE : View.GONE);
        if (bottomNav != null) bottomNav.setVisibility(wide ? View.GONE : View.VISIBLE);
    }

    /** Keep both nav widgets on the same selected tab without re-triggering the listener. */
    private void syncSelection(int id) {
        navSyncing = true;
        if (bottomNav != null && bottomNav.getSelectedItemId() != id) bottomNav.setSelectedItemId(id);
        if (railNav != null && railNav.getSelectedItemId() != id) railNav.setSelectedItemId(id);
        navSyncing = false;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // configChanges keeps the activity alive (no boot-gate replay); just re-pick the nav.
        applyNavForWidth();
        syncSelection(currentTab);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_TAB, currentTab);
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

    /** ADFA-4837: true while a server start is actually in progress (header shows "Starting…"). */
    public boolean isServerStarting() {
        return Boolean.TRUE.equals(targetServerState);
    }

    /** ADFA-4837: can we safely (re)start the server from the Library home? Only when it's installed,
     *  really idle, and nothing else is in flight — so a retry can never stack over a stop/install. */
    public boolean canStartServer() {
        return !closing
                && targetServerState == null
                && !ServerStateRepository.get().current().alive
                && (serverController == null || !serverController.isStopping())
                && !InstallProgressRepository.get().isRunning()
                && !org.iiab.controller.InstallGuard.inProgress(this)
                && org.iiab.controller.SystemStateEvaluator.isSystemInstalled(this);
    }

    /** ADFA-4837: header "Couldn't start — tap to retry" action. Safe no-op unless truly idle. */
    public void startServer() {
        if (!canStartServer()) return;
        targetServerState = Boolean.TRUE;   // make "starting" explicit for the home header
        serverController.handleServerLaunchClick(findViewById(android.R.id.content));
    }

    /** Settings "Turn off K2Go": full-screen closing scene + graceful teardown, then leave. */
    public void turnOffK2Go() {
        if (closing) return;
        closing = true;
        // ADFA-4834: minimal shutdown feedback — a status line + the service currently stopping,
        // shown over the exit animation and kept until the environment is really stopped, so we
        // never bounce back to the Library mid-shutdown. Works with or without the Lottie.
        stopBootEllipsis();   // ADFA-4837: leaving boot; the shutdown line owns the status now
        if (installProgress != null) {
            installProgress.setVisibility(View.VISIBLE);
            if (installStatus != null) installStatus.setText(getString(R.string.server_shutting_down));
            if (installDetail != null) installDetail.setText("");
            if (installBar != null) installBar.setVisibility(View.GONE);
        }
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
        // The real close is driven by the server-alive observer (closing && !alive -> onClosedReady).
        // A graceful stop can take ~40s (kolibri), so keep only a long last-resort safety; the old 15s
        // fired mid-stop and dumped the user back on the Library.
        new Handler(Looper.getMainLooper()).postDelayed(this::onClosedReady, 120000L);
    }

    private void onClosedReady() {
        if (closedDone) return;
        closedDone = true;
        if (bootGate == null) { finishAndExit(); return; }
        if (reduceMotion()) { finishAndExit(); return; }
        bootGate.removeAllAnimatorListeners();
        bootGate.setRepeatCount(0);
        bootGate.setMinAndMaxFrame("D_CLOSED_FLIP");
        bootGate.addAnimatorListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) { finishAndExit(); }
        });
        bootGate.playAnimation();
    }

    /**
     * ADFA-4834: "Turn off" means off. finishAndRemoveTask() alone only drops the UI/task — the
     * process (and its worker threads) lingers idle, so the app looks "still on" and re-shows a
     * stale, server-down Library on return. Remove the task, then terminate the process. The
     * watchdog (START_STICKY) is already stopped by the teardown, so nothing revives us.
     */
    private void finishAndExit() {
        finishAndRemoveTask();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 200L);
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

    // ADFA-4834: minimal shutdown feedback — show the service currently stopping while closing.
    @Override public void onShutdownProgress(String service) {
        if (!closing || installDetail == null) return;
        installDetail.setText(service);
    }

    // ADFA-4834: teardown really finished (pdsm stop exited, proot killed, watchdog off). This is
    // the primary close trigger; the /home-poll observer and the 120s timeout are only fallbacks.
    @Override public void onShutdownComplete() {
        if (closing) onClosedReady();
    }

    // ADFA-4837: a start began — show an animated "Starting your library…" immediately so the ~15s
    // before the first pdsm line isn't a blank, frozen-looking screen.
    @Override public void onStartupBegan() {
        if (closing || installing || gateDismissed || installProgress == null) return;
        installProgress.setVisibility(View.VISIBLE);
        if (installBar != null) installBar.setVisibility(View.GONE);
        if (installDetail != null) installDetail.setText("");
        startBootEllipsis();
    }

    // ADFA-4837: boot progress — show which service is starting under the boot animation, mirroring
    // the shutdown line, so start/close feel symmetric. Only during the initial boot gate (not during
    // an install, which owns the same overlay, and not while closing).
    @Override public void onStartupProgress(String service) {
        if (closing || installing || gateDismissed || installProgress == null) return;
        installProgress.setVisibility(View.VISIBLE);
        if (installBar != null) installBar.setVisibility(View.GONE);
        startBootEllipsis();   // keep the status line animating; the service shows below
        if (installDetail != null) installDetail.setText(service);
    }

    /** ADFA-4837: cycle "Starting your library" + . / .. / … on the boot status line. */
    private void startBootEllipsis() {
        if (ellipsisRunnable != null) return;   // already animating
        if (bootBaseText == null) {
            bootBaseText = getString(R.string.k2go_starting_library).replaceAll("[\\s.…]+$", "");
        }
        ellipsisRunnable = new Runnable() {
            int i = 0;
            // Fixed 3-slot suffix: dots padded with spaces so the total width never changes. The
            // suffix is rendered monospaced (space == dot advance) so the centered message stays put.
            final String[] frames = {".  ", ".. ", "..."};
            @Override public void run() {
                if (installStatus != null) {
                    String suffix = frames[i % frames.length];
                    android.text.SpannableString sp = new android.text.SpannableString(bootBaseText + suffix);
                    sp.setSpan(new android.text.style.TypefaceSpan("monospace"),
                            bootBaseText.length(), bootBaseText.length() + suffix.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    installStatus.setText(sp);
                }
                i++;
                ellipsisHandler.postDelayed(this, 450L);
            }
        };
        ellipsisHandler.post(ellipsisRunnable);
    }

    private void stopBootEllipsis() {
        if (ellipsisRunnable != null) ellipsisHandler.removeCallbacks(ellipsisRunnable);
        ellipsisRunnable = null;
    }

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
