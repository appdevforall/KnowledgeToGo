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
    private android.widget.TextView installStatus, installDetail, installPercent;
    private android.widget.ProgressBar installBar;
    private boolean gateDismissed = false;
    private boolean closing = false;
    private boolean closedDone = false;
    private boolean recovering = false;   // ADFA-4919 (2c-ii): checking a possibly-damaged killed install

    // ADFA-4837/4947: animated "…" on the boot status + extract-detail lines, via the shared
    // EllipsisAnimator (fixed-width mode so the centered lines don't jiggle as the dots grow).
    private org.iiab.controller.util.EllipsisAnimator bootEllipsis;
    private org.iiab.controller.util.EllipsisAnimator readingEllipsis;

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
        installPercent = findViewById(R.id.k2go_install_percent);
        // ADFA-4947: shared ellipsis animators (fixed-width so the centered lines don't shift).
        bootEllipsis = new org.iiab.controller.util.EllipsisAnimator(installStatus, true);
        readingEllipsis = new org.iiab.controller.util.EllipsisAnimator(installDetail, true);
        // ADFA-4915: extract detail is one middle-ellipsized line so long file names never overlap.
        installDetail.setMaxLines(1);
        installDetail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
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

        // ADFA-4919 (2c-ii): marker set + no live installer (both install repos IDLE) means a proot
        // install was killed. Enter recovery instead of quietly lifting the gate to an empty library.
        recovering = !installing
                && org.iiab.controller.InstallGuard.inProgress(this)
                && !InstallProgressRepository.get().current().isRunning()
                && !org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning();
        android.util.Log.i("K2Go-Recover", "onCreate recovering=" + recovering
                + " marker=" + org.iiab.controller.InstallGuard.inProgress(this)
                + " systemInstalled=" + systemInstalled
                + " instRunning=" + InstallProgressRepository.get().current().isRunning()
                + " modRunning=" + org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning());

        // ADFA-4919 (2c): a proot module install is live (its queue is RUNNING = the service is up).
        // Reopening the app (fresh LibraryActivity, e.g. from the notification) must land on the
        // progress index, not the empty home — open it over the gate; the index drives completion.
        if (!installing && !recovering
                && org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()) {
            startActivity(new android.content.Intent(this, SetupProgressActivity.class));
        }

        serverController = new ServerController(this, this);
        serverController.start();

        ServerStateRepository.get().state().observe(this, s -> {
            if (s == null) return;
            if (closing) {
                if (!s.alive) onClosedReady();
            } else if (s.alive && !installing) {
                // ADFA-4811: don't lift the boot gate on a server the installer transiently brings
                // up mid-install; the gate is dismissed when the install reaches a terminal state.
                // ADFA-4919 (2c-ii): if we were in recovery and the server came up, the system boots
                // after all — the marker was stale; clear it and proceed.
                if (recovering) { recovering = false; org.iiab.controller.InstallGuard.end(this); }
                onServerReady();
            }
        });

        // Keep the gate up while an install runs, showing real progress, and dismiss only
        // when it actually finishes (or fails) — a 2-3 GB download won't beat a timeout.
        InstallProgressRepository.get().state().observe(this, st -> {
            if (st == null || gateDismissed) return;
            if (st.isRunning()) {
                installing = true;
                recovering = false;   // ADFA-4919: a real install is live — not a killed one
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
                    // ADFA-4853: if the wizard banked content, go straight to Finishing setup
                    // (over the library) — no brief stop on the home. That screen shows
                    // "Starting services…" and drains the wishlists when the engine is up.
                    // ADFA-4901: include MapsWishlist — a maps-only wizard run must still open
                    // Finishing setup so MapsProvisioner can drain it (otherwise maps never installs).
                    if (BooksWishlist.size(this) > 0 || ZimWishlist.size(this) > 0 || MapsWishlist.has(this)) {
                        startActivity(new android.content.Intent(this, SetupProgressActivity.class));
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!gateDismissed) onServerReady();
                    }, GATE_SAFETY_MS);
                } else {
                    onServerReady(); // FAILED: lift the gate; land on the library (offline)
                }
            }
        });

        Handler main = new Handler(Looper.getMainLooper());
        if (installing) {
            // A download is in progress: keep the gate and show live progress; dismissal
            // comes from the install reaching SUCCESS/FAILED, not a timeout.
            showInstallProgress(InstallProgressRepository.get().current());
        } else if (recovering) {
            // ADFA-4919 (2c-ii): keep the gate up while we check a possibly-damaged install. Try to
            // bring the server up (a healthy base just needs starting); after GATE_SAFETY_MS the
            // verdict runs. If the server comes up first, the observer above clears the marker.
            serverController.handleServerLaunchClick(findViewById(android.R.id.content));
            main.postDelayed(this::evaluateRecovery, GATE_SAFETY_MS);
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

    /** ADFA-4910: locale-aware "NN%" (explicit Locale so digits localize and lint is happy). */
    private static String pct(int p) {
        return String.format(java.util.Locale.getDefault(), "%d%%", p);
    }

    private void showInstallProgress(InstallState st) {
        if (installProgress == null || st == null || !st.isRunning()) return;
        stopBootEllipsis();   // ADFA-4837: an install owns the status line; stop the boot animation
        installProgress.setVisibility(View.VISIBLE);
        if (installBar != null) installBar.setVisibility(View.VISIBLE);   // ADFA-4837: boot/shutdown hide it
        if (installPercent != null) installPercent.setVisibility(View.GONE); // ADFA-4910: only the determinate extract shows it
        if (st.phase == InstallState.Phase.DOWNLOADING) {
            installStatus.setText(getString(R.string.k2go_downloading_library));
            installBar.setIndeterminate(false);
            installBar.setProgress(st.percent);
            installDetail.setText(pct(st.percent) + (st.speed.isEmpty() ? "" : "  ·  " + st.speed));
        } else if (st.phase == InstallState.Phase.EXTRACTING) {
            // Keep the "Extracting System…" legend on the status line for both sub-phases.
            installStatus.setText(org.iiab.controller.deploy.domain.ExtractProgress.firstLine(
                    getString(R.string.install_status_extracting)));
            if (st.percent < 0) {
                // ADFA-4915: "reading/listing" sub-phase. listEntries() scans the whole archive
                // (~1 min; longer on low-end devices). Indeterminate bar + an animated "reading …"
                // on the DETAIL line (where the % goes), not on the status legend.
                installBar.setIndeterminate(true);
                startReadingEllipsis(getString(R.string.k2go_reading));
            } else {
                // ADFA-4915: determinate extract — real % plus just the current file's basename
                // (no internal path, no counter): one ellipsized line that never overlaps.
                installBar.setIndeterminate(false);
                installBar.setProgress(st.percent);
                // ADFA-4910: the % lives on its own fixed line (always the same spot); the file
                // name gets the line below, so it can grow/shrink without moving the number.
                if (installPercent != null) {
                    installPercent.setVisibility(View.VISIBLE);
                    installPercent.setText(pct(st.percent));
                }
                installDetail.setText(org.iiab.controller.deploy.domain.ExtractProgress.fileLabel(st.message));
            }
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

    private void onServerReady() {
        if (gateDismissed || bootGate == null) {
            return;
        }
        gateDismissed = true;
        hideInstallProgress();
        // ADFA-4932: mount the feedback FAB only once the library is usable — never over the boot
        // gate / install progress. 88dp bottom margin clears the bottom nav. Idempotent.
        org.iiab.controller.feedback.presentation.FeedbackFab.installOn(this, "library", 88);
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // ADFA-4842: an install finished and CLEAR_TOP'd back here (reused instance). Honor EXTRA_TAB so
        // we land on the Library (Home) tab, not whatever tab was showing when the user left — e.g. they
        // opened Module management from Settings, so without this they'd land back on Settings.
        if (intent != null) {
            int tab = intent.getIntExtra(EXTRA_TAB, -1);
            if (tab != -1) { currentTab = tab; showTab(tab); syncSelection(tab); }
        }
        // ADFA-4842: Home is a MONITOR, not an actuator — it does NOT start the server here. The server
        // is started by the actuators: app launch (onCreate boot flow) and the install index at the end
        // of a module batch. Home only observes ServerStateRepository and reflects it.
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

    @Override
    protected void onDestroy() {
        // ADFA-4947: stop the ellipsis animators so their self-reposting Runnable can't outlive the
        // Activity (the Handler would otherwise keep a reference to this screen via the TextViews).
        if (bootEllipsis != null) bootEllipsis.stop();
        if (readingEllipsis != null) readingEllipsis.stop();
        super.onDestroy();
    }

    /** ADFA-4919 (2c-ii): after the recovery timeout, decide whether a killed proot install left the
     *  system usable (stale marker -> proceed) or damaged (-> tell the user to reinstall). */
    private void evaluateRecovery() {
        if (isFinishing() || gateDismissed || !recovering) return;   // observer/terminal may have cleared it
        recovering = false;
        boolean marker = org.iiab.controller.InstallGuard.inProgress(this);
        boolean reachable = ServerStateRepository.get().current().alive;
        org.iiab.controller.install.domain.InterruptedInstallDetector.Verdict v =
                org.iiab.controller.install.domain.InterruptedInstallDetector.evaluate(marker, reachable);
        android.util.Log.i("K2Go-Recover", "verdict=" + v + " marker=" + marker + " reachable=" + reachable);
        if (v == org.iiab.controller.install.domain.InterruptedInstallDetector.Verdict.DAMAGED_REINSTALL) {
            showDamagedDialog();
        } else {
            if (marker) org.iiab.controller.InstallGuard.end(this);   // stale marker — system is usable
            onServerReady();
        }
    }

    /** ADFA-4919 (2c-ii): a proot install was killed and the system can't start. There is no in-app
     *  wipe/repair yet, so the honest remedy is to reinstall the app. Blocking, non-cancelable. */
    private void showDamagedDialog() {
        if (isFinishing()) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.k2go_damaged_title)
                .setMessage(R.string.k2go_damaged_body)
                .setPositiveButton(R.string.k2go_damaged_close, (d, w) -> finishAffinity())
                .show();
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

    /** ADFA-4956: expose the ServerController so Clone can quiesce/boot the environment via the
     *  unconditional startEnvironment()/stopEnvironment() (never the toggle). */
    public ServerController server() { return serverController; }

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

    /** ADFA-4837/4947: cycle "Starting your library" + . / .. / … on the boot status line. */
    private void startBootEllipsis() {
        if (readingEllipsis != null) readingEllipsis.stop();   // boot and reading never run at once
        if (bootEllipsis != null) bootEllipsis.start(getString(R.string.k2go_starting_library));
    }

    private void stopBootEllipsis() {
        if (bootEllipsis != null) bootEllipsis.stop();
        if (readingEllipsis != null) readingEllipsis.stop();
    }

    /** ADFA-4915/4947: animate "reading" + . / .. / … on the DETAIL line while the archive is listed. */
    private void startReadingEllipsis(final String base) {
        if (bootEllipsis != null) bootEllipsis.stop();
        if (readingEllipsis != null) readingEllipsis.start(base);
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
