package org.iiab.controller.redesign;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
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
import org.iiab.controller.system.data.PendingContent;

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
    /**
     * ADFA-5137: the caller knows there is no system and is bringing the user here to get one.
     *
     * <p>Only the wizard's "Copy from a phone" sets it. That choice has to land on the Clone tab with
     * nothing installed and nothing yet in flight, which is precisely the state that otherwise sends
     * the user back to the wizard — so before this ticket the wizard wrote {@code setup_complete} to
     * get past the check, and that lie is the entrance to findings 3 and 5.
     *
     * <p>An Intent extra rather than a stored fact — but its lifetime is the <b>task record</b>, not
     * this navigation, and the difference is worth stating because a first draft of this comment got it
     * wrong in both directions. Android replays the launching Intent when the process is killed and the
     * task is restored, so the extra survives that; swiping the task away is what ends it. And
     * {@code onNewIntent} calls {@code setIntent}, so a later arrival carrying no {@code settingUp}
     * replaces it. Both outcomes are truthful — the user lands on Home, which since ADFA-5137 has a
     * labelled way to install a system — but nobody should read this as "it dies when you navigate".
     */
    public static final String EXTRA_SETTING_UP = "settingUp";
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
    private android.widget.TextView installStatus, installDetail, installPercent, installEta;
    // ADFA-4895: the three-column row, shown only once the transfer is really running.
    private android.widget.TextView dlPercent, dlRate, dlEta;
    private View downloadRow;
    private View installPercentRow;   // ADFA-5118: the %/ETA weighted-column row
    // ADFA-5119: the two controls that end the wait. One button, three labels; one confirmation.
    private View dlActions;
    private com.google.android.material.button.MaterialButton dlToggle, dlCancel;
    /**
     * The outlined button's own tint, remembered so the filled state can be undone.
     *
     * <p>Setting it to null does not mean "back to the default" — it means no tint at all, and a
     * MaterialButton with no tint paints its shape opaque instead of transparent. That is how Pause
     * came out as a black pill with dark text on it.
     */
    private android.content.res.ColorStateList dlToggleTint;
    private android.widget.ProgressBar installBar;
    private boolean gateDismissed = false;
    private boolean closing = false;
    private boolean closedDone = false;
    private boolean recovering = false;   // ADFA-4919 (2c-ii): checking a possibly-damaged killed install
    /** ADFA-5119: a failure left no system — nothing may open the door until the user chooses. */
    private boolean gateHeldForRecovery = false;
    /** ADFA-5119: the service has been told a person is here; it only needs telling once per hold. */
    private boolean userPresenceSent = false;

    // ADFA-4984: own the OTA self-updater (revived; entry point is Settings -> About). We forward the
    // DownloadManager receiver via onResume/onPause and run one silent auto-check per launch.
    private org.iiab.controller.update.presentation.UpdateController updateController;
    private boolean otaAutoChecked = false;

    // ADFA-4837/4947: animated "…" on the boot status + extract-detail lines, via the shared
    // EllipsisAnimator (fixed-width mode so the centered lines don't jiggle as the dots grow).
    private org.iiab.controller.util.EllipsisAnimator bootEllipsis;
    private org.iiab.controller.util.EllipsisAnimator readingEllipsis;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ADFA-5137: nothing here and nothing coming? Run the first-run wizard, then it routes back.
        //
        // This used to read setup_complete, a flag written when an install STARTED and cleared by
        // nobody — so it could say "set up" while the device had no system, and then this branch
        // routed past the wizard forever. That pair is findings 3 and 5 of state-spine.svg. The
        // question was never "did setup happen": it is "is there a system, or one on the way", and
        // that is answerable from the disk and the two markers, none of which can drift from what
        // they describe.
        boolean broughtHereToSetUp = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_SETTING_UP, false);
        if (!broughtHereToSetUp
                && !org.iiab.controller.system.data.SystemFactsReader.hereOrOnTheWay(this)) {
            startActivity(new Intent(this, WizardActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_library);

        // ADFA-4984: OTA self-updater, active on the library screen. The manual entry lives in
        // Settings -> About; onResume runs one silent check and wires the download receiver.
        updateController = new org.iiab.controller.update.presentation.UpdateController(this);

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
        // ADFA-4957/4960: a live clone is app-scoped — RECEIVE in SyncProgressRepository, SEND in
        // CloneSendSession. On a plain reopen (no explicit tab requested) land on the Clone tab so
        // CloneFragment re-binds to the in-progress transfer/share instead of showing Home.
        if (savedInstanceState == null && !getIntent().hasExtra(EXTRA_TAB)
                && (org.iiab.controller.sync.presentation.SyncProgressRepository.get().isActive()
                    || CloneSendSession.isActive())) {   // ADFA-4960: also a live SEND
            currentTab = R.id.nav_clone;
        }
        showTab(currentTab);
        syncSelection(currentTab);

        bootGate = findViewById(R.id.k2go_boot_gate);
        installProgress = findViewById(R.id.k2go_install_progress);
        installStatus = findViewById(R.id.k2go_install_status);
        installBar = findViewById(R.id.k2go_install_bar);
        installDetail = findViewById(R.id.k2go_install_detail);
        installPercentRow = findViewById(R.id.k2go_install_percent_row);
        installPercent = findViewById(R.id.k2go_install_percent);
        installEta = findViewById(R.id.k2go_install_eta);
        downloadRow = findViewById(R.id.k2go_download_row);     // ADFA-4895
        dlPercent = findViewById(R.id.k2go_download_percent);
        dlRate = findViewById(R.id.k2go_download_rate);
        dlEta = findViewById(R.id.k2go_download_eta);
        // ADFA-5119: Pause / Resume (one button) and Cancel (the one that asks first).
        dlActions = findViewById(R.id.k2go_dl_actions);
        dlToggle = findViewById(R.id.k2go_dl_toggle);
        dlCancel = findViewById(R.id.k2go_dl_cancel);
        dlToggleTint = dlToggle.getBackgroundTintList();   // the outlined style's own value
        dlToggle.setOnClickListener(v -> onDownloadToggle());
        dlCancel.setOnClickListener(v -> confirmCancelDownload());
        // ADFA-4947: shared ellipsis animators (fixed-width so the centered lines don't shift).
        bootEllipsis = new org.iiab.controller.util.EllipsisAnimator(installStatus, true);
        readingEllipsis = new org.iiab.controller.util.EllipsisAnimator(installDetail, true);
        // ADFA-4915: extract detail is one middle-ellipsized line so long file names never overlap.
        installDetail.setMaxLines(1);
        installDetail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        // ADFA-4986: also treat a live install as "installing" even when re-entered WITHOUT the extra
        // (tapping the install notification, or a relaunch). isRunning() covers DOWNLOADING/EXTRACTING/
        // PROVISIONING. Otherwise the gate takes the normal-boot path and its autostart/safety timers
        // start the server and OPEN over a system that is still provisioning -> a broken library.
        installing = getIntent().getBooleanExtra(EXTRA_INSTALLING, false)
                || InstallProgressRepository.get().current().isRunning();
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
                // ADFA-5143 (plan B): not when the user was brought here to get a system. A killed
                // clone-receive leaves the marker set, so this predicate was true again the moment
                // recovery's own suggestion — Reinstall → Copy from a phone — landed on this screen:
                // dialog, recovery, wizard, clone, dialog. A loop out of the exit.
                //
                // The extra already means "the caller knows there is no usable system and is bringing
                // this person somewhere to fix it", which is exactly when a dialog announcing that the
                // system is broken has nothing to add and everything to block. Leave and come back
                // cold and the marker still puts them in recovery, correctly: they are no longer
                // mid-attempt.
                && !broughtHereToSetUp
                && org.iiab.controller.InstallGuard.inProgress(this)
                && !InstallProgressRepository.get().current().isRunning()
                && !org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()
                // ADFA-4971: a LIVE deep-env op (backup/restore, clone-receive) legitimately holds
                // InstallGuard. Without this it was mistaken for a killed install → false "reinstall"
                // dialog, and (via the !recovering guard) it blocked the return-to-op routing so a
                // reopen fell to the boot gate instead of the op screen. ownerHeld self-heals after a
                // true kill, so a genuinely interrupted restore still enters recovery.
                && !org.iiab.controller.env.EnvironmentLock.ownerHeld(this);
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

        // ADFA-4957: same idea for a live deep-env op (backup/restore). A fresh LibraryActivity — from
        // the notification, or a swipe-away relaunch — must land back on the op screen, not Home/Library
        // (which fights the gate and would try to boot the server mid-op). Route straight to the
        // backup/restore index; BackupJobFragment re-binds to the live op from DeepOpProgressRepository.
        if (!installing && !recovering
                && org.iiab.controller.deepop.DeepOpProgressRepository.get().isRunning()) {
            org.iiab.controller.deepop.DeepOpState dop = org.iiab.controller.deepop.DeepOpProgressRepository.get().current();
            String brMode = dop.owner == org.iiab.controller.env.EnvironmentLock.Owner.RESTORE
                    ? BackupJobFragment.MODE_RESTORE : BackupJobFragment.MODE_BACKUP;
            startActivity(new android.content.Intent(this, SetupLibraryActivity.class)
                    .putExtra(SetupLibraryActivity.EXTRA_BACKUP_RESTORE, true)
                    .putExtra(SetupLibraryActivity.EXTRA_BR_JOB_MODE, brMode));
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
                // ADFA-5119: the user gave up on this install. The service has already removed the
                // partial download, the half-written rootfs and the wishlists, and cleared
                // setup_complete — so there is no system for the gate to lift onto. Falling through
                // to the FAILED branch below would land on an empty library, which is the dead end
                // this ticket exists to close. Go back to the decision instead: the tier and the
                // content, chosen again from the start.
                if (st.phase == InstallState.Phase.CANCELLED) {
                    if (!isFinishing()) {
                        startActivity(new android.content.Intent(this, SetupLibraryActivity.class));
                        finish();
                    }
                    return;
                }
                // ADFA-5119: a failure that leaves no system does not open the door. The service
                // keeps the install marker for exactly this case, so the test is the same one the
                // recovery path already uses — and so is the dialog. Its wording ("the setup was
                // stopped before it finished, and the system can't start") is true here without a
                // word changed, and from it the user reaches a restore or a fresh choice.
                //
                // The reason is deliberately NOT appended to the body: it arrives as
                // install_error_download wrapped around Aria2Exit.label(), which is documented as
                // English for logs. Naming the cause on screen needs localized lines per PERMANENT
                // kind, and that belongs with the reworking of this dialog, not here.
                if (st.phase == InstallState.Phase.FAILED
                        && org.iiab.controller.InstallGuard.inProgress(this)
                        && !gateHeldForRecovery) {   // already shown by the recovery verdict
                    // Latched before the dialog, and checked by onServerReady(): the safety timeout
                    // scheduled at onCreate refuses only while `installing` is true, and the line
                    // above has just set it false. Without this, an install that went live after
                    // onCreate (ADFA-4986) would let that timer open the door behind the dialog.
                    gateHeldForRecovery = true;
                    // ADFA-5119 (review): claim the recovery so the timed verdict cannot repeat it.
                    // The activity is recreated on a dark/light toggle (configChanges has no uiMode);
                    // onCreate re-derives recovering=true from the marker and schedules
                    // evaluateRecovery, then the retained FAILED state reaches the fresh observer and
                    // shows the dialog — and the timer showed a second, stacked, non-cancelable one.
                    recovering = false;
                    showDamagedDialog();
                    return;
                }
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
                    // ADFA-4901 asked for maps here, ADFA-4954 for courses; the list is now kept
                    // in one place, because a type missing from THIS line is a type that is never
                    // drained at all — the screen that would have drained it never opens.
                    if (PendingContent.anyBanked(this)) {
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

        // ADFA-5343 (Phase 3): a deep-env op (backup/restore) releases the lock on finishing and sets
        // desired=UP (DeepOpService / CloneFragment); the reconciler observes holder==NONE and boots the
        // box via its one actuator, so Home no longer boots it here. The once-per-op latch (lastDeepOpSeq)
        // and this observer-driven handleServerLaunchClick are gone — the way back has a single owner now.
        // The old guards it carried (not-alive, no owner held, no transition in flight) are subsumed by
        // desired: while the op holds the lock the holder is STOPPED-class so desired is DOWN (no mid-op
        // boot), and the reconciler's actuation is idempotent (no double-launch).

        Handler main = new Handler(Looper.getMainLooper());
        if (installing) {
            // A download is in progress: keep the gate and show live progress; dismissal
            // comes from the install reaching SUCCESS/FAILED, not a timeout.
            showInstallProgress(InstallProgressRepository.get().current());
        } else if (recovering) {
            // ADFA-4919 (2c-ii): keep the gate up while we check a possibly-damaged install. Try to
            // bring the server up (a healthy base just needs starting); after GATE_SAFETY_MS the
            // verdict runs. If the server comes up first, the observer above clears the marker.
            //
            // ADFA-5119: when there is no rootfs on disk, that wait is 25 seconds of a closed gate
            // and nothing else — measured on a force-stop during a paused download. Nothing can
            // start, so the verdict is already known and waiting only delays saying it. Asked of the
            // disk via rootfsPresent(): isSystemInstalled() cannot answer, because the marker that
            // put us in recovery is what makes it false.
            //
            // The same constant already exists one branch below for this exact reasoning: nothing
            // installed, nothing to boot, do not sit there.
            boolean nothingToBoot = !org.iiab.controller.SystemStateEvaluator.rootfsPresent(this);
            if (!nothingToBoot) {
                serverController.handleServerLaunchClick(findViewById(android.R.id.content));
            }
            main.postDelayed(this::evaluateRecovery,
                    nothingToBoot ? NO_SYSTEM_GATE_MS : GATE_SAFETY_MS);
        } else if (org.iiab.controller.env.EnvironmentLock.ownerHeld(this)) {
            // ADFA-4960: a deep-env op (clone/backup/restore) holds the lock, so the server is
            // intentionally STOPPED. Don't sit behind the boot gate waiting for a server that won't
            // come up (that was the "reopen during a clone loads forever" bug) — lift it now and show
            // the UI we routed to (e.g. the Clone tab). The op boots the server when it finishes
            // (CloneFragment.releaseCloneEnv / the DeepOp terminal observer), never the boot gate.
            onServerReady();
        } else {
            // If the stack isn't up after one poll cycle, start it.
            if (systemInstalled) {
                main.postDelayed(() -> {
                    // ADFA-4986: never autostart the server if an install went live after onCreate.
                    if (!isFinishing() && !installing
                            && !ServerStateRepository.get().current().alive
                            && targetServerState == null) {
                        serverController.handleServerLaunchClick(findViewById(android.R.id.content));
                    }
                }, AUTOSTART_DELAY_MS);
            }
            // Safety: never trap the user behind the gate — but ADFA-4986: don't lift it mid-install.
            // Deliberate trade-off: while an install is live there is intentionally NO safety-timeout
            // dismissal here; the gate is lifted only when the install reaches a terminal state (the
            // InstallProgressRepository observer: SUCCESS starts the server then opens, FAILED opens
            // to the offline library) — the same contract as the first-run `if (installing)` branch,
            // which also has no safety net. A genuinely hung install (no terminal) is a separate
            // concern owned by the installer, not something to paper over by opening a broken system.
            main.postDelayed(() -> {
                if (!gateDismissed && !installing) {
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
        // ADFA-5119: the figure rows RESERVE their line instead of collapsing it. Only one of the two
        // is ever filled, but a GONE row shortened the panel — and the panel's height is what places
        // the animation above it, so every phase change nudged the whole screen. INVISIBLE keeps the
        // line, so the status text, the bar, the figures and the shopfront all hold their positions
        // from the first byte to the last. Same complaint ADFA-4910 and ADFA-5118 fixed for the
        // percentage, one level up: nothing should dance.
        // Exactly ONE of the two is laid out at any moment — the other is GONE — so the panel always
        // has one figure line and never one or two. INVISIBLE rather than GONE for the default,
        // because a collapsed row shortened the panel, and the panel's height is what places the
        // animation above it: every phase change was nudging the whole screen. Reserving the line
        // holds the status text, the bar, the figures and the shopfront still from the first byte to
        // the last — the complaint ADFA-4910 and ADFA-5118 already answered for the percentage.
        if (installPercentRow != null) installPercentRow.setVisibility(View.INVISIBLE);
        if (downloadRow != null) downloadRow.setVisibility(View.GONE);   // ADFA-4895
        renderDownloadActions(st);   // ADFA-5119
        // Re-arm the presence latch whenever the download is not held: the next hold is a new window
        // and deserves to be told again.
        if (!st.isSoftFailed()) userPresenceSent = false;
        if (st.isHeld()) {
            // ADFA-5119: stopped, and it has to look stopped. The status line says so instead of
            // "Downloading your library…", which would be a lie while nothing moves, and the bar
            // keeps its position because the bytes are still on disk — a bar that dropped to zero
            // would suggest the transfer was lost. Rate and estimate are gone: with nothing moving
            // there is no rate to report, and an estimate would be a guess about when the user will
            // press the button. That leaves one figure, so it takes the two-column row.
            //
            // Both held states render the same way; only the line differs. A pause needs no
            // explanation because the user did it, and a stop needs one because they did not — so
            // SOFTFAILED brings its own already-localized reason and PAUSED reads its label here.
            installStatus.setText(st.isSoftFailed() && !st.message.isEmpty()
                    ? st.message : getString(R.string.k2go_dl_paused));
            installBar.setIndeterminate(false);
            installBar.setProgress(st.percent);
            if (installPercentRow != null) {
                installPercentRow.setVisibility(View.VISIBLE);
                installPercent.setText(pct(st.percent));
                installPercent.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                installEta.setText("");
                installEta.setVisibility(View.GONE);
            }
            installDetail.setText("");
        } else if (st.phase == InstallState.Phase.DOWNLOADING) {
            // ADFA-5119: while a retry is in flight the note owns this line. Every attempt restarts
            // from the IPv4/IPv6 probe, so without it the same three probes scroll past a second and
            // a third time with nothing to say which time this is — and the count is precisely what
            // tells the user whether waiting is still worth it.
            installStatus.setText(st.message.isEmpty()
                    ? getString(R.string.k2go_downloading_library) : st.message);
            installBar.setIndeterminate(false);
            installBar.setProgress(st.percent);
            // ADFA-4895: one table per line, sized to the line, rather than one table stretched
            // over both. Before the transfer starts the line carries two things — "0%" and
            // "Test IPv4" — and pushing those through a three-column row leaves a visible hole
            // where the third would be. Once a rate and an estimate are both real there are three,
            // and they get a row built for three. Either way every figure sits in a fixed column,
            // so a rate swinging between "412KiB/s" and "37MiB/s" cannot drag the % sideways —
            // the dance ADFA-4910 and ADFA-5118 already fixed for extract.
            boolean threeUp = !st.speed.isEmpty() && !st.eta.isEmpty();
            if (threeUp && downloadRow != null) {
                // The three-column row replaces the two-column one rather than joining it: one line
                // either way, so the handover does not change the panel's height.
                if (installPercentRow != null) installPercentRow.setVisibility(View.GONE);
                downloadRow.setVisibility(View.VISIBLE);
                dlPercent.setText(pct(st.percent));
                dlRate.setText(st.speed);
                dlEta.setText("·  " + st.eta);
            } else if (installPercentRow != null) {
                installPercentRow.setVisibility(View.VISIBLE);
                installPercent.setText(pct(st.percent));
                boolean hasSecond = !st.speed.isEmpty();
                installEta.setText(hasSecond ? st.speed : "");
                installEta.setVisibility(hasSecond ? View.VISIBLE : View.GONE);
                // Alone, the % owns the row and centres — the rule ADFA-5118 already applies
                // before its own estimate appears.
                installPercent.setGravity(hasSecond
                        ? android.view.Gravity.END : android.view.Gravity.CENTER_HORIZONTAL);
            }
            installDetail.setText("");
        } else if (st.phase == InstallState.Phase.VERIFYING || st.phase == InstallState.Phase.EXTRACTING) {
            // ADFA-5118: the unified verify+extract bar. Both passes render identically — determinate
            // bar + % + ETA + current file — so there is no "first nothing, then detail" asymmetry.
            // Only the verb on the status line changes at the handoff.
            boolean verifying = st.phase == InstallState.Phase.VERIFYING;
            installStatus.setText(org.iiab.controller.deploy.domain.ExtractProgress.firstLine(
                    getString(verifying ? R.string.k2go_verifying_files : R.string.install_status_extracting)));
            if (st.percent < 0) {
                // Indeterminate fallback: before the first byte lands, or an archive whose size we
                // couldn't read (no byte-based %). Animated hint on the DETAIL line (where the % goes).
                installBar.setIndeterminate(true);
                startReadingEllipsis(getString(R.string.k2go_reading));
            } else {
                // Determinate — real % plus the current file's basename (no path, no counter): one
                // ellipsized line that never overlaps.
                installBar.setIndeterminate(false);
                installBar.setProgress(st.percent);
                // ADFA-4910: the % lives on its own fixed line (always the same spot); the file
                // name gets the line below, so it can grow/shrink without moving the number.
                // ADFA-5118: % and ETA sit in two weighted columns — the ETA (st.speed) can change
                // width without shoving the %, so the number stays put across "3 min"->"almost done".
                if (installPercentRow != null) {
                    installPercentRow.setVisibility(View.VISIBLE);
                    installPercent.setText(pct(st.percent));
                    // ADFA-5118: with no ETA yet (verify, or extract before a rate is known) the %
                    // owns the whole row — centre it (there is space to spare). Once the ETA appears
                    // (past the 50% handoff) hide-nothing: the % slides to the ~40% pivot and the ETA
                    // fills to its right, so the pair reads balanced. Hiding the ETA cell (GONE) lets
                    // the weighted % cell take the full width to centre against.
                    boolean hasEta = !st.speed.isEmpty();
                    installEta.setText(hasEta ? "·  " + st.speed : "");
                    installEta.setVisibility(hasEta ? View.VISIBLE : View.GONE);
                    installPercent.setGravity(hasEta
                            ? android.view.Gravity.END : android.view.Gravity.CENTER_HORIZONTAL);
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
        if (dlActions != null) dlActions.setVisibility(View.GONE);   // ADFA-5119
        // ADFA-5119: the lift is NOT undone here, deliberately. This runs the instant the install
        // reaches a terminal, and the very next thing is the gate's OPEN flip — so dropping the
        // animation back down first made it jump once more, on the last frame the user sees. Once an
        // install has raised it, it stays raised for the life of this Activity; after the flip the
        // view is GONE and the value stops mattering. A launch with no install never raises it at
        // all, which is why the ordinary boot still uses the full screen.
    }

    /**
     * ADFA-5119: which of the labels is true right now, and whether the pair is offered at all.
     *
     * <p>Only a transfer can be paused or abandoned. Verify, extract and provisioning are not
     * offered the controls at all — there is no safe point to stop an extract, and a rootfs left
     * half-written is exactly the unnameable state this ticket exists to remove.
     */
    private void renderDownloadActions(InstallState st) {
        if (dlActions == null) return;
        // ADFA-5119: the buttons RESERVE their row for the whole install, whether or not they are
        // offered. Verify, extract and provisioning have no control to give, but collapsing the row
        // there made the panel two rows shorter and dropped the animation by that much at every
        // handover — a jolt in the middle of an operation whose whole point is that it looks steady.
        // The space costs nothing: it is the bottom of a screen with nothing else on it.
        boolean offered = st.phase == InstallState.Phase.DOWNLOADING || st.isHeld();
        dlActions.setVisibility(offered ? View.VISIBLE : View.INVISIBLE);
        liftGateForActions(true);
        if (!offered) return;
        // One button, three labels, and the state picks which one is true. Retry and Resume run the
        // same code — aria2 continues from its control file either way — so the label is the only
        // thing that distinguishes "you stopped this" from "something stopped this".
        dlToggle.setText(st.isSoftFailed() ? R.string.k2go_dl_retry
                : st.isPaused() ? R.string.k2go_dl_resume : R.string.k2go_dl_pause);
        // ADFA-5119: Material 3 says "this is the action now" with emphasis, not with movement —
        // filled outranks tonal outranks outlined outranks text. During a download, Pause is a
        // secondary offer beside a transfer doing fine, so it stays outlined. Once the download has
        // stopped on its own, Retry IS the primary action and takes the filled treatment.
        //
        // Deliberately not an attention animation. A pulsing button says "hurry", and the moment the
        // user reaches for it the hurry is gone — the first touch cancels the window and the state
        // then waits as long as they like. It would be pressuring someone we have just given
        // unlimited time, which is the same reason nothing draws a countdown.
        boolean primary = st.isSoftFailed();
        // Brand teal, not ink: a filled primary action is the same gesture the wizard's "Internet
        // download" makes, and it should look like the same app. k2go_boot_accent rather than
        // k2go_teal because that one flips to a pale turquoise at night and this paper does not.
        dlToggle.setBackgroundTintList(primary
                ? android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(this, R.color.k2go_boot_accent))
                : dlToggleTint);
        dlToggle.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                primary ? R.color.k2go_boot_bg : R.color.k2go_boot_ink));
        // Both controls, always. A first pass hid Pause during the IPv4/IPv6 probe on the assumption
        // that the probe carries no rate — the device showed otherwise: it reports "Test IPv6" in
        // the rate slot, so the guard never fired. Dropped rather than repaired, because what it was
        // protecting against turns out to be acceptable. `stopRequest` is latched and cleared per
        // run (Aria2Manager:125), so a pause tapped during the probe applies to the transfer that
        // follows and lands at 0% instead of doing nothing. Distinguishing the probe honestly would
        // mean carrying a "not transferring yet" marker in the state, which is more model for a
        // button that already behaves.
        dlToggle.setVisibility(View.VISIBLE);
    }

    /**
     * ADFA-5119: make room under the animation for the panel that covers it.
     *
     * <p>Padding on the animation rather than a margin on the panel, because the Lottie is
     * {@code fitCenter}: padding is the box it fits inside, so the drawing re-centres instead of
     * being clipped. Set from here rather than in XML so a normal boot — no install, no panel —
     * still uses the full height.
     *
     * <p><b>The figure is the panel's own height, not a constant.</b> A first pass used 56dp and on
     * a device nothing moved, which is arithmetic rather than bad luck: fitCenter centres inside
     * {@code height - padding}, so the drawing's centre sits at {@code (H - P) / 2}. To centre it in
     * the space above a panel of height {@code A}, P has to equal A — and the panel is well over
     * 200dp with the status line, the bar, the figures, the detail and now two buttons. Measuring it
     * also means the animation follows the panel when a row appears or goes.
     */
    private void liftGateForActions(boolean lifted) {
        if (bootGate == null) return;
        if (lifted && installProgress != null && installProgress.getHeight() == 0) {
            // Asked before the panel has been laid out (the first render happens in onCreate). Come
            // back once it has a height to report — but re-derive `lifted` from the panel rather than
            // passing true again: if a terminal arrives in between, hideInstallProgress() makes it
            // GONE, its height stays 0, and a runnable that reposted itself unconditionally would
            // repost forever and hold the Activity through the view.
            installProgress.post(() -> {
                if (installProgress == null || isFinishing()) return;
                liftGateForActions(installProgress.getVisibility() == View.VISIBLE);
            });
            return;
        }
        int pad = (lifted && installProgress != null) ? installProgress.getHeight() : 0;
        if (bootGate.getPaddingBottom() != pad) {
            bootGate.setPadding(bootGate.getPaddingLeft(), bootGate.getPaddingTop(),
                    bootGate.getPaddingRight(), pad);
        }
    }

    /**
     * ADFA-5119: tell the service somebody is here, once, while the download is held.
     *
     * <p>A held download fails through to recovery after a minute so an unattended install cannot sit
     * there until morning. That protection is wrong the moment there is a person in front of it, and
     * a touch is the only honest evidence of one — so the first touch drops the clock and the state
     * then waits as long as they need.
     *
     * <p>Latched, because this fires on every touch event and the service only needs telling once.
     * Reset when the phase leaves the held state, in {@link #showInstallProgress}.
     */
    /**
     * ADFA-5119 (review): presence expires when the screen does.
     *
     * <p>The first touch drops the held window; without this, that drop was permanent — touch once,
     * walk away, and the install waits forever with nobody to answer it. Leaving is the honest end of
     * "somebody is here", so it hands the clock back.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (userPresenceSent && InstallProgressRepository.get().current().isSoftFailed()) {
            userPresenceSent = false;
            sendToInstallService(
                    org.iiab.controller.install.presentation.InstallService.ACTION_USER_ABSENT);
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (userPresenceSent) return;
        if (!InstallProgressRepository.get().current().isSoftFailed()) return;
        userPresenceSent = true;
        sendToInstallService(org.iiab.controller.install.presentation.InstallService.ACTION_USER_PRESENT);
    }

    /** ADFA-5119: Pause and Resume are the same button, so they are the same tap. */
    private void onDownloadToggle() {
        InstallState st = InstallProgressRepository.get().current();
        if (st.isHeld()) {
            sendToInstallService(org.iiab.controller.install.presentation.InstallService.ACTION_RESUME);
        } else if (st.phase == InstallState.Phase.DOWNLOADING) {
            sendToInstallService(org.iiab.controller.install.presentation.InstallService.ACTION_PAUSE);
        }
    }

    /**
     * ADFA-5119: Cancel asks first, because it is not the loud twin of Pause.
     *
     * <p>Pause keeps the bytes and the decision behind them; Cancel gives up both — the transfer
     * starts over from nothing and the tier and content are chosen again. The body names both
     * losses. It does not repeat the percentage: that figure is on screen behind this dialog, and a
     * second copy of it here would be a second place formatting the same fact.
     */
    private void confirmCancelDownload() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.k2go_dl_cancel_title)
                .setMessage(R.string.k2go_dl_cancel_body)
                .setNegativeButton(R.string.k2go_dl_cancel_keep, null)
                .setPositiveButton(R.string.k2go_dl_cancel_confirm, (d, w) ->
                        sendToInstallService(
                                org.iiab.controller.install.presentation.InstallService.ACTION_CANCEL))
                .show();
    }

    /**
     * ADFA-5119: plain {@code startService}, deliberately, not {@code startForegroundService}.
     *
     * <p>These actions are only ever offered while the service is demonstrably alive — it is the
     * thing that posted the DOWNLOADING or PAUSED state being rendered. Their branches answer
     * without calling {@code startForeground}, so promoting the start would leave Android waiting
     * for a foreground notification that never comes if the service had already gone away:
     * ForegroundServiceDidNotStartInTimeException, on a stale button tap. The same reasoning is
     * written out at {@code ContentStateInvalidator.replacementStarting}.
     */
    private void sendToInstallService(String action) {
        try {
            startService(new Intent(this,
                    org.iiab.controller.install.presentation.InstallService.class).setAction(action));
        } catch (Exception e) {
            Log.w(TAG, "could not deliver " + action + " to the install service", e);
        }
    }

    private void onServerReady() {
        if (gateDismissed || bootGate == null) {
            return;
        }
        // ADFA-5119: a failed build left no system, and the user has a blocking choice on screen.
        // Every caller of this method — the alive observer, both safety timeouts, the recovery
        // verdict — means "it is safe to show the library now", and here it is not: there is nothing
        // behind the gate. One latch, checked at the single place the gate opens, rather than the
        // same condition repeated at four call sites.
        if (gateHeldForRecovery) {
            return;
        }
        gateDismissed = true;
        hideInstallProgress();
        maybeAutoCheckUpdate();   // ADFA-4984: gate is open now — safe to run the one-per-launch check
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
        if (updateController != null) updateController.registerDownloadReceiver();
        maybeAutoCheckUpdate();   // ADFA-4984: deferred until the boot gate has opened
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (serverController != null) serverController.onPause();
        if (updateController != null) updateController.unregisterDownloadReceiver();
    }

    /** ADFA-4984: exposed so Settings -> About can trigger a manual "Check for updates". */
    public org.iiab.controller.update.presentation.UpdateController updateController() {
        return updateController;
    }

    /** ADFA-4984: one silent OTA check per launch, but only once the boot gate has opened (so an
     *  "update available" dialog never lands over the gate) and never during a first install. Called
     *  from onResume and from onServerReady, whichever settles last; guarded to run at most once. */
    private void maybeAutoCheckUpdate() {
        if (updateController == null || otaAutoChecked || installing || !gateDismissed) return;
        otaAutoChecked = true;
        updateController.checkForUpdates(false);
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
            // ADFA-5119 (review): the guard was one-directional. The observer's branch latched
            // `recovering` before showing the dialog, but this path set neither latch — so a FAILED
            // post arriving afterwards stacked a second non-cancelable dialog, and with
            // gateHeldForRecovery unset a server coming up could open the gate behind it.
            gateHeldForRecovery = true;
            showDamagedDialog();
        } else {
            if (marker) org.iiab.controller.InstallGuard.end(this);   // stale marker — system is usable
            onServerReady();
        }
    }

    /** ADFA-4919 (2c-ii) / ADFA-5023: a proot install was killed and the system can't start. Instead of
     *  the old dead-end (only "Close" -> finishAffinity, which dumped the user out of the app), offer an
     *  in-app recovery: open Backup & restore, where they can restore a backup OR reinstall from scratch.
     *  Both paths work without a healthy rootfs. Blocking, non-cancelable; "Close" still exits. */
    private void showDamagedDialog() {
        if (isFinishing()) return;
        androidx.appcompat.app.AlertDialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.k2go_damaged_title)
                .setMessage(R.string.k2go_damaged_body)
                .setPositiveButton(R.string.k2go_damaged_recover, (dlg, w) -> {
                    SetupLibraryActivity.recover(this);   // ADFA-5150: the shared route
                    finish();   // the dialog closes so the user can't fall back onto the held gate
                })
                // ADFA-5119: report it from here, where the user is standing when it matters. The app
                // knows what happened and they do not, so the description is filled from the install
                // log rather than left as a blank box in front of someone who just watched a download
                // give up. The screenshot the report captures is this dialog, which is the right
                // picture. Routing is ADFA-5130's, so email keeps the attachment and Slack gets the
                // text.
                .setNeutralButton(R.string.k2go_damaged_report, null)
                .setNegativeButton(R.string.k2go_damaged_close, (dlg, w) -> finishAffinity())
                .create();
        // Attached after show() so the neutral button does NOT dismiss: reporting is not a decision
        // about the system, and the two that are — recover, or close — must still be there
        // afterwards. A dialog that vanished on "Report" would leave the user behind a closed gate
        // with nothing to press.
        d.setOnShowListener(dlg -> d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> org.iiab.controller.feedback.presentation.FeedbackFab
                        .sendFeedback(this, "install-failed",
                                org.iiab.controller.feedback.domain.FeedbackType.BUG,
                                installFailureReport())));
        d.show();
    }

    /**
     * ADFA-5119: what the install was doing when it gave up, as the body of a report.
     *
     * <p>The log is already in memory — {@code LogRepository} collects the pipeline's lines for the
     * in-app view — and the failure path does not kill the process, so it is still there when this
     * dialog appears. Only the tail: the interesting part of a download failure is its last seconds,
     * and a two-thousand-line body is a report nobody reads.
     */
    private String installFailureReport() {
        java.util.List<String> all = org.iiab.controller.LogRepository.get().snapshot();
        int from = Math.max(0, all.size() - 80);
        StringBuilder sb = new StringBuilder("Install did not finish. Last lines:\n");
        for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
        return sb.toString();
    }

    /** ADFA-5023: while the boot gate is up during an install/reinstall (e.g. "wiping old system"), Back
     *  must NOT walk back out through the setup screens — you're in "let me work" territory. Send the app
     *  to the background instead (reopening resumes the gate); the install keeps running. Outside an
     *  install, Back behaves normally. */
    @Override
    public void onBackPressed() {
        if (installing && !gateDismissed) { moveTaskToBack(true); return; }
        super.onBackPressed();
    }

    private boolean reduceMotion() {
        // ADFA-5143: the reading moved to util/Motion so the clone screen answers this the same way
        // this one does, instead of keeping a private copy of the same setting.
        return org.iiab.controller.util.Motion.reduced(this);
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
                // ADFA-5143: the last two align this with ServerController.handleServerLaunchClick,
                // which is the guard that actually decides. This method was a PARTIAL copy of it —
                // missing the module queue and the environment lock — so it said yes where the real
                // guard says no, and the header offered a Retry that flickered and did nothing. The
                // start was never in danger; the button was a lie about it. Two places answering "can
                // I start the server?" and answering differently is the defect, not the clone.
                //
                // ownerHeld covers a clone on either side without knowing anything about clones:
                // Owner.CLONE is in the enum and both sides acquire it (CloneFragment:354 and :936).
                && !org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()
                && !org.iiab.controller.env.EnvironmentLock.ownerHeld(this)
                && org.iiab.controller.SystemStateEvaluator.isSystemInstalled(this);
    }

    /**
     * ADFA-5143: take the user to the Clone tab, where a transfer in flight actually lives.
     *
     * <p>The Home header offers this instead of a restart during a transfer. For the receiver it is
     * the only place the progress exists; for the donor it is where the QR and Stop are — which is
     * why Stop is not copied onto the header. Navigating to a control beats owning a second one.
     */
    public void openCloneTab() {
        currentTab = R.id.nav_clone;
        showTab(currentTab);
        syncSelection(currentTab);
    }

    /** ADFA-5151: land on Home/Library — the success exit of a clone-receive (a system now exists),
     *  mirroring the install index's goHome. The server was booted by CloneFragment.releaseCloneEnv,
     *  so LibraryHomeFragment refreshes onto the new system. */
    public void openLibraryTab() {
        currentTab = R.id.nav_library;
        showTab(currentTab);
        syncSelection(currentTab);
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
