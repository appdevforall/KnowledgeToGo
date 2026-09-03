package org.appdevforall.k2go.redesign;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import org.appdevforall.k2go.ApkServer;
import org.appdevforall.k2go.BuildConfig;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.ServerController;
import org.appdevforall.k2go.SyncHandshakeHelper;
import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.sync.domain.ApkShareName;
import org.appdevforall.k2go.hotspot.LocalHotspotManager;
import org.appdevforall.k2go.sync.domain.ShareConfig;
import org.appdevforall.k2go.sync.presentation.SyncProgressRepository;
import org.appdevforall.k2go.sync.presentation.SyncStateViewModel;
import org.appdevforall.k2go.sync.presentation.SyncTransferState;
import org.appdevforall.k2go.sync.transport.NetworkInterfaces;
import org.appdevforall.k2go.sync.transport.NetworkStateLiveData;
import org.appdevforall.k2go.sync.transport.QrCodec;
import org.appdevforall.k2go.sync.transport.TransportEngine;

/**
 * Clone tab — Send side (ADFA-4777): copy the whole system to another device. Two-code flow per
 * the final design (Join → Start), reusing the existing rsync transfer backend (TransportEngine +
 * SyncHandshakeHelper), the LocalOnly hotspot, and QrCodec. "Stop sharing" is Clone-only (the copy
 * is heavy and stoppable). Receive lands in a follow-up PR under the same ticket.
 */
public class CloneFragment extends Fragment {

    private enum Side { SEND, RECEIVE }
    private enum Mode { HOTSPOT, WIFI }
    // ADFA-5154: the 3-step Send wizard (Join / Get app / Copy) becomes 2 pages. PREPARE shows Join ①
    // and Get app ② stacked (both QRs at once); COPY shows the transfer QR. The "Installed? Copy the
    // library ›" gate advances PREPARE -> COPY (a real prerequisite: the app must be on the other phone).
    private enum Page { PREPARE, COPY }

    private Side side = Side.SEND;
    private Mode mode = Mode.HOTSPOT;
    private Page page = Page.PREPARE;
    private boolean startedStep = false;

    private final LocalHotspotManager hs = LocalHotspotManager.get();
    private TransportEngine transport;
    private ShareConfig shareConfig;
    private String tempPass;
    private boolean daemonStarted = false, daemonStarting = false, hostHasRootfs = false;
    private LibrarySize.Split librarySplit;  // ADFA-4780: approx system/content sizes (computed on share)
    private LinearLayout shareCard;
    private TextView sizeSys, sizeContent, sizeTotal;
    private boolean userStopped = false;  // true after Stop, prevents auto-restart on the next render
    private boolean protectionOn = false; // ADFA-4782: foreground WatchdogService currently held
    private boolean cloneLockHeld = false;  // ADFA-4956: this fragment holds EnvironmentLock(CLONE)
    private boolean cloneGuardHeld = false; // ADFA-4956: a receive (destructive write) also set InstallGuard
    private boolean shareAnyway = false;  // ADFA-4786: user chose to share even with no library installed
    private boolean systemPresent = true; // ADFA-5150: Send needs a system to send; refreshed on entry / onResume
    // ADFA-5155: the success exit waits for the REST core to actually answer (apiReady) before it counts
    // down and redirects — never a fixed timer that could drop the user onto a dead Home or condemn a
    // good transfer. State: pop-up acknowledged, services confirmed up, a probe in flight, the user chose
    // to keep waiting past the "slow" nudge, and the 3-2-1 countdown running.
    private boolean exitInProgress = false, exitAck = false, exitServicesUp = false;
    private boolean exitProbing = false, exitKeepWaiting = false, exitCountingDown = false;
    private long exitStartAt = 0L;
    private int exitSecs = 0;
    private final android.os.Handler exitHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private org.appdevforall.k2go.util.EllipsisAnimator exitDots;
    private LinearLayout rcvExit;
    private TextView rcvExitStatus, rcvExitRescan, rcvExitWait;
    private static final long EXIT_POLL_MS = 2000L;      // reschedule the apiReady probe (like the index)
    private static final long EXIT_SLOW_MS = 30000L;     // after this, offer a re-scan instead of condemning
    private static final int  EXIT_REDIRECT_SECS = 3;    // literal countdown once services are up
    // ADFA-5151: once a receive is accepted (running), Back is confined — first press warns, the next
    // backgrounds the app (the copy keeps running under CloneShareService; the notification returns).
    private androidx.activity.OnBackPressedCallback receiveBackGate;
    private boolean leaveWarned = false;
    // ADFA-5154: the APK is served over HTTP from the Get-app section on Page 1 (was a sub-screen).
    private ApkServer apkServer;
    private String apkFileName;

    private ActivityResultLauncher<String> locationPerm;
    // ADFA-5146: request the location permission at most once per attempt. ensureHotspot() runs on every
    // render, and the permission callback re-renders; on OEMs that return a denied permission result
    // synchronously that becomes unbounded recursion (render -> ensureHotspot -> launch -> sync-deny ->
    // callback -> render …), overflowing the stack. This latch breaks the loop; setMode() clears it so a
    // Hotspot/Wi-Fi switch is a natural retry path.
    private boolean locationAsked = false;

    private TextView tabSend, tabReceive, tabHotspot, tabWifi, caption, subCaption, footer;
    // ADFA-5346: the "Installed? copy the library" advance CTA is a MaterialButton (shape/size from the
    // shared style); styleAdvance only toggles fill/emphasis (filled teal vs teal-text), not the shape.
    private com.google.android.material.button.MaterialButton advance;
    // ADFA-5346: the footer action button morphs across roles (recover/share-anyway/stop/start). It is a
    // MaterialButton rebuilt per role via a ThemeOverlay (setStopRole) so the role look stays only in the
    // XML styles — no color recipe duplicated here.
    private com.google.android.material.button.MaterialButton stop;
    // ADFA-4785: intent fork (Send / Receive) replaces the persistent top toggle.
    private boolean atFork = true;
    private LinearLayout forkBox, tabsRow;
    private TextView cloneHdr, subtitleView, backHeader;
    private TextView stepTitle, shareWifi;
    // ADFA-5154: Page 1 stacks two shared QR sections (Join ① + Get app ②); Page 2 is Copy.
    private QrSection secJoin, secGetApp;
    private TextView secJoinBadge, secJoinTitle;   // Join title is mode-dependent; not part of QrSection
    private LinearLayout page1, page2, getAppBanner;
    // Receive side
    private SyncStateViewModel syncVm;
    private LinearLayout receiveBox, progressBox;
    private EditText paste;
    private TextView receiveStart, pStatus, pFile, pStats, cancel;
    private LinearProgressIndicator pbar;   // ADFA-5143: typed as itself, so setProgressCompat is available
    private com.airbnb.lottie.LottieAnimationView anim;   // ADFA-5143: the clone loop
    // ADFA-5143: read once — renderReceive() runs on every progress tick and this is a Settings read.
    private boolean reduceMotion = false;
    private long lastSeq = -1L;
    private LinearLayout confirmPanel, confirmSizes, confirmReplace, confirmFresh;
    private TextView confirmSys, confirmContent, confirmTotal;
    private enum RStage { JOIN, START }
    private RStage rStage = RStage.JOIN;
    private boolean pasteExpanded = false;
    private ActivityResultLauncher<ScanOptions> barcodeLauncher;
    private LinearLayout rcvSteps, rcvIntro, rcvNotice, pasteBlock;
    private TextView rcvCaption, rcvScan, rcvSub, rcvSkip, rcvSkipHint, rcvCamNote, rcvShowPaste;
    // ADFA-4784: incompatibility hard-block state (receiver). >=0 means "showing not-compatible"
    // for a scanned/pasted code advertising that host architecture.
    private LinearLayout rcvIncompat;
    private TextView incompatWhy, incompatWhyText, incompatTech, incompatTechText, incompatBack;
    private int incompatHostBits = -1;
    private boolean incompatWhyOpen = false, incompatTechOpen = false;
    private LinearLayout netRow, steps;
    private ImageView qr;
    private TextView showcode, codetext, copyBtn, shareBtn;
    private LinearLayout codeblock;
    private LinearLayout actionFooter;   // ADFA-5154: pinned wrapper for the stop/start/recover button
    private android.widget.ScrollView contentScroll;   // ADFA-5154: scrolls back to top on Hotspot/Wi-Fi switch
    private org.appdevforall.k2go.util.EllipsisAnimator startingDots;   // ADFA-5154: animated "Starting service…"
    private String currentPayload = "";

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        locationPerm = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) hs.start(requireContext().getApplicationContext()); render(); });
        barcodeLauncher = registerForActivityResult(new ScanContract(), r -> onScan(r.getContents()));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_k2go_clone, c, false);
        tabSend = v.findViewById(R.id.k2go_clone_send);
        tabReceive = v.findViewById(R.id.k2go_clone_receive);
        netRow = v.findViewById(R.id.k2go_clone_net);
        tabHotspot = v.findViewById(R.id.k2go_clone_hotspot);
        tabWifi = v.findViewById(R.id.k2go_clone_wifi);
        steps = v.findViewById(R.id.k2go_clone_steps);
        qr = v.findViewById(R.id.k2go_clone_qr);
        caption = v.findViewById(R.id.k2go_clone_caption);
        subCaption = v.findViewById(R.id.k2go_clone_subcaption);
        // ADFA-5154: the two stacked sections (Page 1) + the page containers. Sections bind through the
        // include root + the shared sec_* ids (ADFA-5157).
        page1 = v.findViewById(R.id.k2go_clone_page1);
        page2 = v.findViewById(R.id.k2go_clone_page2);
        getAppBanner = v.findViewById(R.id.k2go_clone_getapp_banner);
        View joinRoot = v.findViewById(R.id.k2go_clone_sec_join);
        View getAppRoot = v.findViewById(R.id.k2go_clone_sec_getapp);
        secJoin = new QrSection(joinRoot, R.id.sec_qr_frame, R.id.sec_qr, R.id.sec_qr_ph,
                R.id.sec_caption, R.id.sec_subcaption, R.id.sec_fallback_toggle, R.id.sec_fallback, R.id.sec_fallback_values);
        secGetApp = new QrSection(getAppRoot, R.id.sec_qr_frame, R.id.sec_qr, R.id.sec_qr_ph,
                R.id.sec_caption, R.id.sec_subcaption, R.id.sec_fallback_toggle, R.id.sec_fallback, R.id.sec_fallback_values);
        // ADFA-5154: badge numbers are static; the Get-app title is static; the Join title is mode-
        // dependent (set in renderPrepare). The include's sec_badge/sec_title aren't part of QrSection.
        secJoinBadge = joinRoot.findViewById(R.id.sec_badge);
        secJoinTitle = joinRoot.findViewById(R.id.sec_title);
        secJoinBadge.setText("1");
        ((TextView) getAppRoot.findViewById(R.id.sec_badge)).setText("2");
        ((TextView) getAppRoot.findViewById(R.id.sec_title)).setText(getString(R.string.k2go_badge_getapp));
        advance = v.findViewById(R.id.k2go_clone_advance);
        stop = v.findViewById(R.id.k2go_clone_stop);
        actionFooter = v.findViewById(R.id.k2go_clone_action_footer);   // ADFA-5154: pinned action button
        contentScroll = v.findViewById(R.id.k2go_clone_scroll);
        footer = v.findViewById(R.id.k2go_clone_footer);
        startingDots = new org.appdevforall.k2go.util.EllipsisAnimator(caption, true);   // animated "Starting service…"
        shareCard = v.findViewById(R.id.k2go_clone_sharecard);
        sizeSys = v.findViewById(R.id.k2go_clone_size_sys);
        sizeContent = v.findViewById(R.id.k2go_clone_size_content);
        sizeTotal = v.findViewById(R.id.k2go_clone_size_total);
        receiveBox = v.findViewById(R.id.k2go_clone_receive_box);
        paste = v.findViewById(R.id.k2go_clone_paste);
        receiveStart = v.findViewById(R.id.k2go_clone_receive_start);
        rcvSteps = v.findViewById(R.id.k2go_rcv_steps);
        rcvCaption = v.findViewById(R.id.k2go_rcv_caption);
        rcvIntro = v.findViewById(R.id.k2go_rcv_intro);
        rcvNotice = v.findViewById(R.id.k2go_rcv_notice);
        rcvScan = v.findViewById(R.id.k2go_rcv_scan);
        rcvSub = v.findViewById(R.id.k2go_rcv_sub);
        rcvSkip = v.findViewById(R.id.k2go_rcv_skip);
        rcvSkipHint = v.findViewById(R.id.k2go_rcv_skiphint);
        rcvCamNote = v.findViewById(R.id.k2go_rcv_camnote);
        rcvShowPaste = v.findViewById(R.id.k2go_rcv_showpaste);
        pasteBlock = v.findViewById(R.id.k2go_rcv_pasteblock);
        rcvIncompat = v.findViewById(R.id.k2go_rcv_incompat);
        incompatWhy = v.findViewById(R.id.k2go_rcv_incompat_why);
        incompatWhyText = v.findViewById(R.id.k2go_rcv_incompat_whytext);
        incompatTech = v.findViewById(R.id.k2go_rcv_incompat_tech);
        incompatTechText = v.findViewById(R.id.k2go_rcv_incompat_techtext);
        incompatBack = v.findViewById(R.id.k2go_rcv_incompat_back);
        incompatWhy.setOnClickListener(x -> { incompatWhyOpen = !incompatWhyOpen; renderReceive(); });
        incompatTech.setOnClickListener(x -> { incompatTechOpen = !incompatTechOpen; renderReceive(); });
        incompatBack.setOnClickListener(x -> { incompatHostBits = -1; incompatWhyOpen = false; incompatTechOpen = false; renderReceive(); });
        progressBox = v.findViewById(R.id.k2go_clone_progress);
        rcvExit = v.findViewById(R.id.k2go_rcv_exit);   // ADFA-5155: success-exit status zone (receive step 2)
        rcvExitStatus = v.findViewById(R.id.k2go_rcv_exit_status);
        rcvExitRescan = v.findViewById(R.id.k2go_rcv_exit_rescan);
        rcvExitWait = v.findViewById(R.id.k2go_rcv_exit_wait);
        exitDots = new org.appdevforall.k2go.util.EllipsisAnimator(rcvExitStatus);
        rcvExitRescan.setOnClickListener(x -> exitRescan());
        rcvExitWait.setOnClickListener(x -> { exitKeepWaiting = true; renderReceive(); });
        pStatus = v.findViewById(R.id.k2go_clone_pstatus);
        pbar = v.findViewById(R.id.k2go_clone_pbar);
        // ADFA-5143: the transfer state uses the same Lottie template as the backup / restore job
        // screen. The animation ships without icons; the two K2Go logos are composited by the layout.
        anim = v.findViewById(R.id.k2go_clone_anim);
        reduceMotion = org.appdevforall.k2go.util.Motion.reduced(getContext());
        if (anim != null) {
            anim.setAnimation(R.raw.k2go_clone_loop);
            if (reduceMotion) {
                // Reduce motion: hold one frame — the glows and a few dots still read as "in flight",
                // and the screen never depends on movement to be understood.
                anim.setProgress(0.5f);
            } else {
                anim.setRepeatCount(com.airbnb.lottie.LottieDrawable.INFINITE);
            }
        }
        pFile = v.findViewById(R.id.k2go_clone_pfile);
        pStats = v.findViewById(R.id.k2go_clone_pstats);
        cancel = v.findViewById(R.id.k2go_clone_cancel);
        confirmPanel = v.findViewById(R.id.k2go_rcv_confirm);
        confirmSizes = v.findViewById(R.id.k2go_rcv_confirm_sizes);
        confirmSys = v.findViewById(R.id.k2go_rcv_confirm_sys);
        confirmContent = v.findViewById(R.id.k2go_rcv_confirm_content);
        confirmTotal = v.findViewById(R.id.k2go_rcv_confirm_total);
        confirmReplace = v.findViewById(R.id.k2go_rcv_confirm_replace);
        confirmFresh = v.findViewById(R.id.k2go_rcv_confirm_fresh);
        v.findViewById(R.id.k2go_rcv_confirm_go).setOnClickListener(x -> startReceiveTransfer());
        v.findViewById(R.id.k2go_rcv_confirm_cancel).setOnClickListener(x -> { syncVm.cancelProbe(); renderReceive(); });
        showcode = v.findViewById(R.id.k2go_clone_showcode);
        codeblock = v.findViewById(R.id.k2go_clone_codeblock);
        codetext = v.findViewById(R.id.k2go_clone_codetext);
        copyBtn = v.findViewById(R.id.k2go_clone_copy);
        shareBtn = v.findViewById(R.id.k2go_clone_share);
        // ADFA-5154: the Get-app QR lives inline on Page 1 (secGetApp); "Can't scan? Share another way"
        // hands the installed APK to the Android share sheet.
        v.findViewById(R.id.k2go_sendapp_share).setOnClickListener(x -> shareApkViaSheet());

        syncVm = new ViewModelProvider(requireActivity()).get(SyncStateViewModel.class);
        transport = syncVm.getTransport();
        shareConfig = ShareConfig.defaults();

        tabSend.setOnClickListener(x -> setSide(Side.SEND));
        tabReceive.setOnClickListener(x -> setSide(Side.RECEIVE));
        tabHotspot.setOnClickListener(x -> requestMode(Mode.HOTSPOT));
        tabWifi.setOnClickListener(x -> requestMode(Mode.WIFI));
        advance.setOnClickListener(x -> {
            // ADFA-5154: the one gate. PREPARE (Join + Get app) -> COPY when the app is installed on the
            // other phone; COPY -> back to PREPARE.
            page = (page == Page.PREPARE) ? Page.COPY : Page.PREPARE;
            render();
        });
        // ADFA-5236: no reveal toggle — the code is shown always under a fixed label (see showCodeAsText).
        copyBtn.setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("K2Go transfer code", currentPayload));
            Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_code_copied), Toast.LENGTH_SHORT).show();
        });
        shareBtn.setOnClickListener(x -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentPayload);
            startActivity(Intent.createChooser(i, getString(R.string.k2go_clone_chooser_send_code)));
        });

        receiveStart.setOnClickListener(x -> onReceiveStart());
        cancel.setOnClickListener(x -> onReceiveCancel());
        rcvScan.setOnClickListener(x -> {
            if (rStage == RStage.JOIN) openWifiSettings();
            else launchScanner(getString(R.string.k2go_clone_scan_prompt_receive));
        });
        rcvSkip.setOnClickListener(x -> { rStage = RStage.START; render(); });
        rcvShowPaste.setOnClickListener(x -> { pasteExpanded = !pasteExpanded; renderReceive(); });

        hs.state().observe(getViewLifecycleOwner(), st -> render());
        // ADFA-5064: same rescue as Connect — redraw the handshake/URL QR when the device's network
        // changes from outside the app (Wi-Fi turned on/off, roam, new IP lease). render() re-reads
        // the IP via discover(), so a QR that was blank for "no network" fills in once one appears.
        NetworkStateLiveData.get(requireContext()).observe(getViewLifecycleOwner(), net -> render());
        SyncTransferState cur = SyncProgressRepository.get().current();
        lastSeq = (cur != null) ? cur.seq : -1L;   // only fire dialogs on NEW transitions
        SyncProgressRepository.get().state().observe(getViewLifecycleOwner(), this::onTransferState);

        cloneHdr = v.findViewById(R.id.k2go_clone_hdr);
        subtitleView = v.findViewById(R.id.k2go_clone_subtitle);
        backHeader = v.findViewById(R.id.k2go_clone_back);
        forkBox = v.findViewById(R.id.k2go_clone_fork);
        tabsRow = v.findViewById(R.id.k2go_clone_tabs);
        stepTitle = v.findViewById(R.id.k2go_clone_steptitle);
        shareWifi = v.findViewById(R.id.k2go_clone_sharewifi);
        shareWifi.setOnClickListener(x -> openWifiSettings());
        v.findViewById(R.id.k2go_clone_fork_send).setOnClickListener(x -> enterSide(Side.SEND));
        v.findViewById(R.id.k2go_clone_fork_receive).setOnClickListener(x -> enterSide(Side.RECEIVE));
        backHeader.setOnClickListener(x -> goToFork());
        // ADFA-5151: the receive confinement, mirroring BackupJobFragment's backGate. Enabled only once
        // the transfer is accepted (running, past CONFIRM) via updateBackGuard(); before that Back is
        // free. First Back warns; the next backgrounds the app rather than landing on a systemless Home.
        receiveBackGate = new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                if (!leaveWarned) {
                    leaveWarned = true;
                    org.appdevforall.k2go.util.Snackbars.make(
                            requireActivity().findViewById(android.R.id.content),
                            R.string.k2go_clone_back_running).show();
                } else {
                    requireActivity().moveTaskToBack(true);   // leave; the copy survives, the notification returns
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), receiveBackGate);
        // ADFA-4960: re-bind to a live SEND session. Its rsync daemon is still up in this process (kept
        // alive by CloneShareService), so a recreated Fragment must land on the share screen — not the
        // fork. Restore the state the share screen redraws from; daemonStarted=true makes ensureDaemon's
        // guard skip a re-start.
        if (CloneSendSession.isActive()) {
            atFork = false; side = Side.SEND; page = Page.COPY;
            mode = CloneSendSession.isHotspot() ? Mode.HOTSPOT : Mode.WIFI;
            tempPass = CloneSendSession.tempPass();
            hostHasRootfs = CloneSendSession.hostHasRootfs();
            shareAnyway = CloneSendSession.shareAnyway();
            librarySplit = CloneSendSession.split();
            daemonStarted = true;
            cloneLockHeld = true;   // the CLONE lock is still held by this process's send session
        } else if (SyncProgressRepository.get().isActive()) {
            // ADFA-5152: the symmetric RECEIVE re-bind, which the SEND comment above used to claim
            // already existed. A recreated Fragment during a live receive (the notification tapped, or
            // the app reopened) must land on the progress screen, not the fork — the receiver's
            // percentage is the only progress there is. renderReceive() paints it from the live state;
            // this just selects the receive side. The CLONE lock and install guard are still held by
            // this process's receive, so mark them held (re-derived, like showReceiveTerminal) — a
            // later cancel goes through releaseCloneEnv(), which is gated on these.
            atFork = false; side = Side.RECEIVE;
            cloneLockHeld = true;
            Context rebindCtx = getContext();
            cloneGuardHeld = rebindCtx != null && org.appdevforall.k2go.InstallGuard.isLive(rebindCtx);   // ADFA-5343 (Phase 5a): our own live marker, this process
        } else if (!org.appdevforall.k2go.SystemStateEvaluator.isSystemInstalled(requireContext())) {
            // ADFA-5151: no system → skip the Send/Receive fork and land on receive step 1. Send is
            // blocked anyway (ADFA-5150), so Receive is the only real move; going straight to it is the
            // funnel Recover feeds. Free until the transfer is accepted — the back guard bites only once
            // it is running.
            atFork = false; side = Side.RECEIVE; rStage = RStage.JOIN;
        }
        render();
        return v;
    }

    private void enterSide(Side sd) { atFork = false; page = Page.PREPARE; setSide(sd); }

    private void goToFork() { atFork = true; render(); }

    private void setSide(Side sd) {
        side = sd;
        if (sd == Side.SEND) {
            // ADFA-5150: Send shares this device's library — with no system there is nothing to send,
            // the same dead surface as Connect. Refresh the fact; render() shows a no-system empty
            // state (message + Recover) at the entry rather than an abrupt jump or a snackbar. Receive
            // stays open: it is how you GET a system. Don't spin up the hotspot when there is nothing
            // to serve. (This also covers "send the app" buried under Send — a deliberate simplification;
            // a phone with a system is the natural source for both the app and the system.)
            systemPresent = org.appdevforall.k2go.SystemStateEvaluator.isSystemInstalled(requireContext());
            page = Page.PREPARE;
            if (systemPresent) setMode(Mode.HOTSPOT); else render();   // ADFA-4785: enter Send at step 1
            return;
        }
        rStage = RStage.JOIN; pasteExpanded = false;
        incompatHostBits = -1; incompatWhyOpen = false; incompatTechOpen = false;   // ADFA-4784: fresh on entry
        render();
    }

    // ADFA-4785: switching the network at step 3 (Copy) drops the active connection and cuts any
    // copy in progress. Warn first; elsewhere (or when the mode is unchanged) switch directly.
    private void requestMode(Mode target) {
        boolean sharing = (side == Side.SEND && page == Page.COPY && daemonStarted);
        if (target == mode || !sharing) { setMode(target); return; }
        String label = (target == Mode.HOTSPOT) ? "Hotspot" : "Wi-Fi";
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.k2go_clone_switch_title, label))
                .setMessage(getString(R.string.k2go_clone_switch_msg))
                .setNegativeButton(getString(R.string.k2go_cancel), null)
                .setPositiveButton(getString(R.string.k2go_clone_switch_confirm), (d, w) -> setMode(target))
                .show();
    }

    private void setMode(Mode m) {
        mode = m;
        locationAsked = false;   // ADFA-5146: a mode switch is a fresh attempt — allow one more perm prompt
        if (m == Mode.HOTSPOT) ensureHotspot();
        render();   // ADFA-4785: keep the current step; switching Hotspot/Wi-Fi no longer resets to step 1
        // ADFA-5154: Hotspot ① has a QR, Wi-Fi ① doesn't, so the two tabs differ in height — switching
        // mid-scroll would leave the view offset. Snap back to the top (Connect avoids this: single QR
        // that fits one screen).
        if (contentScroll != null) contentScroll.post(() -> contentScroll.smoothScrollTo(0, 0));
    }

    private void ensureHotspot() {
        if (!LocalHotspotManager.isSupported() || hs.isOn()) return;
        // ADFA-5158: renderPrepare runs on every render; don't re-request a start while one is in flight
        // (the "Caller already has an active LocalOnlyHotspot request" log spam).
        LocalHotspotManager.State st = hs.state().getValue();
        if (st != null && st.phase == LocalHotspotManager.Phase.STARTING) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            hs.start(requireContext().getApplicationContext());
        } else if (!locationAsked) {
            // ADFA-5146: launch the request exactly once. Do NOT re-launch on later renders (incl. the one
            // the permission callback triggers) — a synchronous deny would otherwise recurse into overflow.
            locationAsked = true;
            locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    /** Start the rsync share daemon once we have a LAN IP (off the main thread). */
    private void ensureDaemon(String ip) {
        if (daemonStarted || daemonStarting || userStopped || ip == null) return;
        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        boolean hasLib = rootfsPresent();
        if (!hasLib && !shareAnyway) return;   // ADFA-4786: don't silently share an empty library; renderStartState shows the notice
        // ADFA-4956: serving a live rootfs yields a torn clone. Coordinate as a deep-env op — refuse if
        // another one (install/backup/restore) holds the lock, else acquire(CLONE) and stop the server
        // so the served tree is static; the daemon starts only after the stop completes.
        if (!cloneLockHeld && EnvironmentLock.isHeld(requireContext())) {
            Toast.makeText(requireContext(), org.appdevforall.k2go.util.BusyMessage.resFor(requireContext()), Toast.LENGTH_LONG).show();
            return;
        }
        daemonStarting = true;
        tempPass = SyncHandshakeHelper.generateSecurePassword();
        hostHasRootfs = hasLib;
        if (!rootfsDir.exists()) rootfsDir.mkdirs();
        final String shareDir = rootfsDir.getAbsolutePath();
        final Context app = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity act = requireActivity();  // capture before the thread
        final File iiabRoot = rootfsDir;  // effectively final for the worker
        EnvironmentLock.acquire(app, EnvironmentLock.Owner.CLONE);
        cloneLockHeld = true;
        final Runnable startDaemon = () -> new Thread(() -> {
            final boolean ok = transport.startServer(app, shareConfig, tempPass, shareDir);
            final LibrarySize.Split split = LibrarySize.compute(iiabRoot);  // ADFA-4780: approx sizes for the QR + overview
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                daemonStarting = false; daemonStarted = ok; librarySplit = split;
                if (ok) CloneSendSession.begin(mode == Mode.HOTSPOT, tempPass, hostHasRootfs, shareAnyway, split);  // ADFA-4960: app-scope the live share so a recreated Fragment re-binds
                else releaseCloneEnv();   // couldn't serve — boot the server back, drop the lock
                render();
            });
        }, "clone-share-daemon").start();
        ServerController sc = server();
        if (sc != null) sc.stopEnvironment(startDaemon);   // quiesce first, THEN serve a static tree
        else startDaemon.run();
    }

    /** ADFA-4786: true only when a real library is installed (dir exists and is non-empty). */
    private boolean rootfsPresent() {
        File d = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        String[] kids = d.isDirectory() ? d.list() : null;
        return kids != null && kids.length > 0;
    }

    /**
     * This app's ACTUAL installed ABI width, read from nativeLibraryDir — not the device's 64-bit
     * capability. A 32-bit install on a 64-bit phone must report 32, because the rootfs/library arch
     * follows the app's install ABI, not the hardware.
     * (ADFA-4784: the earlier Build.SUPPORTED_64_BIT_ABIS check wrongly passed 32-on-64 as compatible.)
     */
    private int archBits() {
        try {
            String dir = requireContext().getApplicationInfo().nativeLibraryDir;
            if (dir != null) {
                if (dir.contains("arm64") || dir.contains("x86_64") || dir.endsWith("64")) return 64;
                if (dir.contains("arm") || dir.contains("x86")) return 32;
            }
        } catch (Exception ignored) { }
        return (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0) ? 64 : 32;
    }

    // ------------------------------------------------------------ Background protection (ADFA-4782)
    // A Clone transfer runs a long native rsync (Send serves a daemon; Receive pulls). If the app is
    // backgrounded or the screen locks, Android can freeze the app and its native children and the
    // phantom-process killer can SIGKILL rsync (exit 137). The foreground WatchdogService holds CPU +
    // Wi-Fi locks with a notification to keep it alive. Protection tracks the actual transfer state:
    // on while the Send daemon is up OR a Receive pull is active, off otherwise.
    private void syncProtection() {
        boolean active = daemonStarted || SyncProgressRepository.get().isActive();
        if (active) startProtection(); else stopProtection();
    }

    private void startProtection() {
        if (protectionOn) return;
        Context ctx = getContext();
        if (ctx == null) return;
        Intent i = new Intent(ctx, CloneShareService.class).setAction(CloneShareService.ACTION_START);   // ADFA-4960: clone-specific keep-alive (notification returns to the Clone tab)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
        protectionOn = true;
        Log.i("IIAB-Clone", "clone share protection ON");
    }

    private void stopProtection() {
        if (!protectionOn) return;
        protectionOn = false;
        Context ctx = getContext();
        if (ctx == null) return;   // detached; the service is app-scoped and stops on its own teardown path
        ctx.startService(new Intent(ctx, CloneShareService.class).setAction(CloneShareService.ACTION_STOP));
        Log.i("IIAB-Clone", "clone share protection OFF");
    }

    // ------------------------------------------------------------ Deep-env coordination (ADFA-4956)
    // Clone owns the rootfs exclusively while it runs: Send serves a live tree (torn copy) and Receive
    // overwrites the live system (destructive, like Restore). Both must hold EnvironmentLock(CLONE) and
    // run with the server stopped, then boot it back after. Receive also sets InstallGuard so a killed
    // extract is recoverable. Uses the unconditional start/stopEnvironment (never the toggle).
    private ServerController server() {
        return (getActivity() instanceof LibraryActivity) ? ((LibraryActivity) getActivity()).server() : null;
    }

    /** Boot the (possibly replaced) system back and drop the deep-env lock. Idempotent; no-op if we
     *  don't hold it. Called on every terminal path (stop / complete / cancel / detach-when-idle). */
    private void releaseCloneEnv() {
        if (!cloneLockHeld) return;
        Context ctx = getContext();
        if (cloneGuardHeld && ctx != null) org.appdevforall.k2go.InstallGuard.end(ctx);
        cloneGuardHeld = false;
        // ADFA-5343 (Phase 3): don't boot the server here. Set desired=UP (the persisted intent) and drop
        // the lock; the reconciler observes holder==NONE and brings the box back via its one actuator —
        // the way back is no longer this fragment's job.
        if (ctx != null) new org.appdevforall.k2go.Preferences(ctx).setWatchdogEnable(true);
        if (ctx != null) EnvironmentLock.release(ctx);
        cloneLockHeld = false;
        Log.i("IIAB-Clone", "clone env released (desired=UP, lock dropped; reconciler boots)");
    }

    private void render() {
        if (!isAdded() || caption == null) return;
        if (startingDots != null) startingDots.stop();   // ADFA-5154: only the daemon-starting state re-starts it
        updateBackGuard();   // ADFA-5151: keep the Back confinement in step with side + transfer state
        if (showcode != null) { showcode.setVisibility(View.GONE); codeblock.setVisibility(View.GONE); }
        if (stepTitle != null) { stepTitle.setVisibility(View.GONE); shareWifi.setVisibility(View.GONE); }
        paintTab(tabSend, side == Side.SEND);
        paintTab(tabReceive, side == Side.RECEIVE);

        if (atFork) {
            cloneHdr.setVisibility(View.VISIBLE);
            subtitleView.setVisibility(View.VISIBLE);
            forkBox.setVisibility(View.VISIBLE);
            tabsRow.setVisibility(View.GONE);
            backHeader.setVisibility(View.GONE);
            netRow.setVisibility(View.GONE);
            steps.setVisibility(View.GONE);
            page1.setVisibility(View.GONE);
            page2.setVisibility(View.GONE);
            advance.setVisibility(View.GONE);
            actionFooter.setVisibility(View.GONE);
            footer.setVisibility(View.GONE);
            shareCard.setVisibility(View.GONE);
            receiveBox.setVisibility(View.GONE);
            return;
        }
        forkBox.setVisibility(View.GONE);
        tabsRow.setVisibility(View.GONE);
        cloneHdr.setVisibility(View.GONE);
        subtitleView.setVisibility(View.GONE);
        backHeader.setVisibility(View.VISIBLE);
        backHeader.setText(getString(side == Side.RECEIVE ? R.string.k2go_clone_back_receive : R.string.k2go_clone_back_send));

        if (side == Side.RECEIVE) {
            netRow.setVisibility(View.GONE);
            steps.setVisibility(View.GONE);
            page1.setVisibility(View.GONE);
            page2.setVisibility(View.GONE);
            advance.setVisibility(View.GONE);
            actionFooter.setVisibility(View.GONE);
            footer.setVisibility(View.GONE);
            shareCard.setVisibility(View.GONE);
            receiveBox.setVisibility(View.VISIBLE);
            renderReceive();
            syncProtection();
            return;
        }
        receiveBox.setVisibility(View.GONE);
        if (!systemPresent) {   // ADFA-5150: empty state, like Connect — nothing to send
            // ADFA-5312: a system op in progress (install/module holds the marker) is not "no system" —
            // show busy, not Recover. systemPresent stays isSystemInstalled() on purpose: a clone-send
            // holds no install marker, so it still renders the normal Send path above.
            if (org.appdevforall.k2go.system.data.SystemFactsReader.verdict(requireContext())
                    == org.appdevforall.k2go.system.domain.SystemVerdict.State.INSTALLING) {
                renderBusySend();
            } else {
                renderNoSystemSend();
            }
            return;
        }
        // ADFA-5154: Send is two pages. Common chrome, then the page.
        actionFooter.setVisibility(View.GONE);   // ADFA-5154: default hidden; only Copy's states re-show it
        netRow.setVisibility(View.VISIBLE);
        paintTab(tabHotspot, mode == Mode.HOTSPOT);
        paintTab(tabWifi, mode == Mode.WIFI);
        steps.setVisibility(View.VISIBLE);
        buildSteps();
        advance.setVisibility(View.VISIBLE);
        if (page == Page.PREPARE) {
            page1.setVisibility(View.VISIBLE);
            page2.setVisibility(View.GONE);
            advance.setText(getString(R.string.k2go_clone_installed_copy));
            styleAdvance(true);
            renderPrepare();
        } else {
            page1.setVisibility(View.GONE);
            page2.setVisibility(View.VISIBLE);
            advance.setText(getString(R.string.k2go_clone_back_step1));
            styleAdvance(false);
            renderCopy();
        }
        syncProtection();
    }

    /**
     * ADFA-5150: no system, so nothing to send — the empty state Connect shows, at the entry to Send
     * rather than three steps in at the old "share anyway" notice. A message plus a Recover action.
     * Reuses {@code stop} as the button; the normal Send paths re-set stop, so this does not stick once
     * a system exists. Reuses k2go_connect_no_system (both Send and Connect are "share your library").
     */
    private void renderNoSystemSend() {
        netRow.setVisibility(View.GONE);
        steps.setVisibility(View.GONE);
        // The empty state draws into Page 2's QR/caption slot, so show that container (and hide Page 1).
        page1.setVisibility(View.GONE);
        page2.setVisibility(View.VISIBLE);
        if (stepTitle != null) stepTitle.setVisibility(View.GONE);
        // Keep the QR placeholder box visible (null bitmap shows its card background), exactly as
        // Connect's no-system state does — this is what makes the two screens read the same instead of
        // Send looking bare and top-crammed.
        qr.setImageBitmap(null); qr.setVisibility(View.VISIBLE);
        caption.setVisibility(View.VISIBLE);
        caption.setText(getString(R.string.k2go_connect_no_system));
        subCaption.setVisibility(View.GONE);
        showcode.setVisibility(View.GONE); codeblock.setVisibility(View.GONE);
        shareCard.setVisibility(View.GONE);
        advance.setVisibility(View.GONE);
        actionFooter.setVisibility(View.VISIBLE);
        setStopRole(R.style.ThemeOverlay_K2Go_Button_Filled, R.string.k2go_home_recover,
                v -> SetupLibraryActivity.recover(requireContext()));
        footer.setVisibility(View.GONE);
    }

    /**
     * ADFA-5312: a system op (install / module / deep-op) is in progress — the system is present but the
     * server is down, so there is nothing to send yet and Recover would be wrong. Mirrors the no-system
     * empty state but says "busy" and offers no action; render() reruns on resume when the op finishes.
     */
    private void renderBusySend() {
        netRow.setVisibility(View.GONE);
        steps.setVisibility(View.GONE);
        page1.setVisibility(View.GONE);
        page2.setVisibility(View.VISIBLE);
        if (stepTitle != null) stepTitle.setVisibility(View.GONE);
        qr.setImageBitmap(null); qr.setVisibility(View.VISIBLE);
        caption.setVisibility(View.VISIBLE);
        caption.setText(getString(R.string.k2go_install_busy));
        subCaption.setVisibility(View.GONE);
        showcode.setVisibility(View.GONE); codeblock.setVisibility(View.GONE);
        shareCard.setVisibility(View.GONE);
        advance.setVisibility(View.GONE);
        actionFooter.setVisibility(View.GONE);   // no Recover during an install
        footer.setVisibility(View.GONE);
    }

    /**
     * ADFA-5154: Page 1 — Join ① and Get app ② stacked, both live at once. The other phone joins the
     * network and installs the app from here; when it's installed, the "Installed? Copy the library"
     * gate advances to Page 2. Mirrors Connect's stacked-QR page.
     */
    private void renderPrepare() {
        // ---- Section ① : Join the network ----
        if (mode == Mode.HOTSPOT) {
            secJoinTitle.setText(getString(R.string.k2go_clone_join_hotspot_title));
            shareWifi.setVisibility(View.GONE);
            LocalHotspotManager.State st = hs.state().getValue();
            LocalHotspotManager.Phase phase = (st != null) ? st.phase : LocalHotspotManager.Phase.OFF;
            if (!LocalHotspotManager.isSupported()) {
                secJoin.frame.setVisibility(View.GONE);
                secJoin.setFallback(requireContext(), null);
                secJoin.caption.setText(getString(R.string.k2go_connect_hotspot_unsupported));
                secJoin.subCaption.setText(getString(R.string.k2go_connect_try_wifi));
            } else if (phase == LocalHotspotManager.Phase.FAILED) {
                secJoin.frame.setVisibility(View.GONE);
                secJoin.setFallback(requireContext(), null);
                secJoin.caption.setText(getString(R.string.k2go_connect_hotspot_failed));
                secJoin.subCaption.setText(getString(R.string.k2go_connect_enable_location));
            } else if (phase == LocalHotspotManager.Phase.OFF || phase == LocalHotspotManager.Phase.STARTING) {
                secJoin.frame.setVisibility(View.VISIBLE);
                secJoin.setQr(requireContext(), null, getString(R.string.k2go_connect_starting_hotspot));
                secJoin.setFallback(requireContext(), null);
                secJoin.caption.setText(getString(R.string.k2go_connect_starting_hotspot));
                secJoin.subCaption.setText("");
            } else {
                String ssid = (st.ssid != null) ? st.ssid : "";
                String pass = (st.passphrase != null) ? st.passphrase : "";
                secJoin.frame.setVisibility(View.VISIBLE);
                secJoin.setQr(requireContext(), "WIFI:S:" + ssid + ";T:WPA;P:" + pass + ";;", null);
                secJoin.caption.setText(getString(R.string.k2go_clone_point_camera_join));
                secJoin.subCaption.setText("");
                secJoin.setFallback(requireContext(), new String[]{
                        getString(R.string.k2go_fallback_wifi, ssid), getString(R.string.k2go_fallback_pass, pass)});
            }
        } else {   // Wi-Fi: "join this Wi-Fi" isn't a QR the other camera can act on — instruct + Share
            secJoinTitle.setText(getString(R.string.k2go_clone_join_wifi_title));
            secJoin.frame.setVisibility(View.GONE);
            secJoin.setQr(requireContext(), null, null);
            secJoin.setFallback(requireContext(), null);
            secJoin.caption.setText(getString(R.string.k2go_clone_join_wifi_note));
            secJoin.subCaption.setText(getString(R.string.k2go_clone_share_wifi_note));
            shareWifi.setVisibility(View.VISIBLE);
        }

        // ---- Section ② : Get the app ---- (own method so the AP-IP poll can redraw just this)
        renderGetAppSection();
    }

    private void renderGetAppSection() {
        if (mode == Mode.HOTSPOT) ensureHotspot();
        startApkServer();
        String appIp = peerReachableIp();
        if (appIp == null || apkServer == null) {
            // K2GO-375: hold with an animated placeholder; the hotspot owner reruns render() when the AP IP
            // lands (Wi-Fi is driven by NetworkStateLiveData), so no local poll is needed.
            secGetApp.setQrPending(requireContext(), getString(R.string.k2go_clone_starting_service));
        } else {
            String url = "http://" + appIp + ":" + shareConfig.apkPort + "/" + apkFileName;
            secGetApp.setQr(requireContext(), url, null);
        }
        secGetApp.caption.setText(getString(R.string.k2go_clone_getapp_caption));
        secGetApp.subCaption.setText("");
        secGetApp.setFallback(requireContext(), null);
        // ADFA-5154: no "STEP 2 BELOW" hint — Connect keeps its equivalent hidden, so Clone matches.
        getAppBanner.setVisibility(View.VISIBLE);
    }

    /**
     * ADFA-5154: Page 2 — Copy the library. Starts the daemon and shows the transfer QR. This is the
     * point of no easy return: the confinement callback (armed at acceptance) keeps the user here.
     */
    private void renderCopy() {
        String ip = peerReachableIp();
        if (ip == null) {
            // ADFA-5158: no IP to advertise. Wi-Fi -> genuinely no network. Hotspot -> the AP IP just
            // hasn't been assigned yet; wait instead of guessing a fixed address that isn't universal
            // across OEMs. K2GO-375: the hotspot owner reruns render() when the AP IP lands, so no local
            // poll is needed here anymore.
            if (mode == Mode.HOTSPOT) simpleState(getString(R.string.k2go_clone_starting_service), "");
            else simpleState(getString(R.string.k2go_connect_no_wifi), getString(R.string.k2go_connect_join_wifi));
            return;
        }
        ensureDaemon(ip);
        renderStartState(ip, mode == Mode.HOTSPOT);
    }

    /**
     * The IP the other phone reaches this one at, per mode — one source for both the get-app URL and the
     * Copy daemon (ADFA-5158; they had diverged: Copy hardcoded 192.168.49.1, get-app had no fallback).
     * Returns null when the address is not assigned yet.
     *
     * <p>K2GO-375: in hotspot mode this reads the reservation's owner (LocalHotspotManager), which re-emits
     * State — and so reruns render() — the moment the AP interface gets its address. That replaced the
     * former local AP-IP poll (netHandler/scheduleNetRetry): one owner drives the redraw instead of each
     * Send page spinning its own timer. Wi-Fi is unchanged — it is the default network, so NetworkStateLiveData
     * already reruns render() when its IP appears.
     */
    private String peerReachableIp() {
        if (mode == Mode.HOTSPOT) {
            LocalHotspotManager.State st = hs.state().getValue();
            return (st != null) ? st.hotspotIp : null;
        }
        return NetworkInterfaces.discover().wifiIp;
    }

    /** Copy state: nothing-to-share -> starting -> stopped (Start sharing) -> running (QR + Stop). */
    private void renderStartState(String ip, boolean twoCode) {
        stepTitle.setVisibility(View.VISIBLE);
        stepTitle.setText(getString(R.string.k2go_clone_step3_title));
        if (!daemonStarted && !daemonStarting && !shareAnyway && !rootfsPresent()) {   // ADFA-4786
            qr.setImageBitmap(null);
            caption.setText(getString(R.string.k2go_clone_nothing_title));
            subCaption.setText(getString(R.string.k2go_clone_no_library_note));
            footer.setText(""); shareCard.setVisibility(View.GONE);
            actionFooter.setVisibility(View.VISIBLE);
            setStopRole(R.style.ThemeOverlay_K2Go_Button_Outlined, R.string.k2go_clone_share_anyway,
                    x -> { shareAnyway = true; render(); });
            return;
        }
        if (daemonStarting) {
            qr.setImageBitmap(null);
            startingDots.start(getString(R.string.k2go_clone_starting_service));   // animated "…"
            subCaption.setText("");
            footer.setText(""); actionFooter.setVisibility(View.GONE); shareCard.setVisibility(View.GONE);
            return;
        }
        if (!daemonStarted) {   // stopped by the user (or failed to start)
            qr.setImageBitmap(null);
            caption.setText(getString(R.string.k2go_clone_sharing_stopped));
            subCaption.setText(getString(R.string.k2go_clone_start_service_note));
            footer.setText(""); shareCard.setVisibility(View.GONE);
            showStartButton();
            return;
        }
        long sysB = (librarySplit != null) ? librarySplit.systemBytes : 0L;
        long contentB = (librarySplit != null) ? librarySplit.contentBytes : 0L;
        String payload = SyncHandshakeHelper.createPayload(ip, shareConfig.rsyncPort, shareConfig.user, tempPass, hostHasRootfs, archBits(), sysB, contentB);
        // ADFA-5236: encode at the rendered QR size (@dimen/k2go_qr_size) so it stays crisp per bucket.
        qr.setImageBitmap(SyncHandshakeHelper.generateQrCode(payload, getResources().getDimensionPixelSize(R.dimen.k2go_qr_size)));
        caption.setText(getString(twoCode ? R.string.k2go_clone_ready_scan2 : R.string.k2go_clone_ready_scan));
        subCaption.setText(""); subCaption.setVisibility(View.GONE);   // ADFA-5154: drop the design-leftover note
        showCodeAsText(payload);
        showStopButton();
        if (librarySplit != null) {
            sizeSys.setText(LibrarySize.human(sysB));
            sizeContent.setText(LibrarySize.human(contentB));
            sizeTotal.setText(LibrarySize.human(librarySplit.totalBytes()));
            shareCard.setVisibility(View.VISIBLE);
        } else {
            shareCard.setVisibility(View.GONE);
        }
        footer.setText(getString(R.string.k2go_clone_stays_on));
    }

    private void startApkServer() {
        if (apkServer != null) return;
        try {
            String apkPath = requireContext().getApplicationInfo().sourceDir;
            String arch = (archBits() == 64) ? "arm64-v8a" : "armeabi-v7a";
            apkFileName = ApkShareName.fileName(BuildConfig.VERSION_NAME, arch);
            apkServer = new ApkServer(shareConfig.apkPort, apkPath, apkFileName);
            apkServer.start();
        } catch (Exception e) {
            Log.e("IIAB-Clone", "APK server failed to start", e);
            Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_app_share_fail), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopApkServer() {
        if (apkServer != null) {
            try { apkServer.stop(); } catch (Exception ignored) { }
            apkServer = null;
        }
    }

    /** Fallback for a phone that can't scan: hand the installed APK to the Android share sheet. */
    private void shareApkViaSheet() {
        try {
            File apk = new File(requireContext().getApplicationInfo().sourceDir);
            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", apk);
            Intent i = new Intent(Intent.ACTION_SEND)
                    .setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.k2go_clone_chooser_share_app)));
        } catch (Exception e) {
            Log.e("IIAB-Clone", "APK share sheet failed", e);
            Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_share_sheet_fail), Toast.LENGTH_SHORT).show();
        }
    }

    /** ADFA-5346: (re)build the footer action button with one of the shared button styles (via a
     *  ThemeOverlay) plus its text + click. actionFooter holds only this button, so we swap it in place.
     *  The role look (filled/outlined/destructive) lives only in the XML styles — nothing is spelled here. */
    private void setStopRole(int overlayTheme, int textRes, View.OnClickListener onClick) {
        com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(
                new android.view.ContextThemeWrapper(requireContext(), overlayTheme), null);
        b.setId(R.id.k2go_clone_stop);   // keep the id stable across rebuilds (a11y / findViewById)
        b.setText(getString(textRes));
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        actionFooter.removeAllViews();
        actionFooter.addView(b);
        stop = b;
    }

    private void showStopButton() {
        actionFooter.setVisibility(View.VISIBLE);
        setStopRole(R.style.ThemeOverlay_K2Go_Button_Destructive, R.string.k2go_clone_stop_sharing, x -> confirmStop());
    }

    private void showStartButton() {
        actionFooter.setVisibility(View.VISIBLE);
        setStopRole(R.style.ThemeOverlay_K2Go_Button_Filled, R.string.k2go_clone_start_sharing,
                x -> { userStopped = false; render(); });
    }

    // ------------------------------------------------------------------ Receive

    private void onReceiveStart() {
        String json = paste.getText().toString().trim();
        if (json.isEmpty()) { Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_paste_first), Toast.LENGTH_SHORT).show(); return; }
        SyncHandshakeHelper.SyncCredentials creds = SyncHandshakeHelper.parsePayload(json);
        if (creds == null) { Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_code_invalid), Toast.LENGTH_LONG).show(); return; }
        if (!archCompatible(creds.archBits)) { showIncompat(creds.archBits); return; }   // ADFA-4784
        probeOrWarnEmpty(creds);   // ADFA-4786
    }

    // ADFA-4784: hard guardrail — a library built for a different CPU can't run here. The sender's
    // arch travels in the QR (creds.archBits); unknown (0) or equal is fine, anything else is blocked.
    private boolean archCompatible(int hostBits) {
        return hostBits == 0 || hostBits == archBits();
    }

    private void showIncompat(int hostBits) {
        incompatHostBits = hostBits;
        incompatWhyOpen = false; incompatTechOpen = false;
        renderReceive();
    }

    private String bitsLabel(int bits) {
        if (bits == 64) return getString(R.string.k2go_arch_64);
        if (bits == 32) return getString(R.string.k2go_arch_32);
        return getString(R.string.k2go_arch_unknown);
    }

    // ADFA-4786: the sender advertises whether it has a library (creds.hasRootfs). If not, there's
    // nothing to copy — warn before probing rather than pulling an empty library.
    private void probeOrWarnEmpty(SyncHandshakeHelper.SyncCredentials creds) {
        if (!creds.hasRootfs) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.k2go_clone_nolib_title))
                    .setMessage(getString(R.string.k2go_clone_nolib_msg))
                    .setNegativeButton(getString(R.string.k2go_cancel), null)
                    .setPositiveButton(getString(R.string.k2go_clone_try_anyway), (d, w) -> syncVm.startProbe(requireContext().getApplicationContext(), shareConfig, creds))
                    .show();
            return;
        }
        syncVm.startProbe(requireContext().getApplicationContext(), shareConfig, creds);
    }

    private void onReceiveCancel() {
        SyncTransferState st = SyncProgressRepository.get().current();
        if (st != null && st.phase == SyncTransferState.Phase.TRANSFERRING) {
            transport.stop();
            releaseCloneEnv();   // ADFA-4956: overwrite aborted mid-pull — boot back, end guard, drop lock
            syncVm.releaseNetwork();
            SyncProgressRepository.get().postIdle();
        } else {
            syncVm.cancelProbe();
        }
        renderReceive();
    }

    /** Observes the shared transfer repository; fires terminal dialogs once per seq. */
    private void onTransferState(SyncTransferState st) {
        if (!isAdded() || st == null) return;
        Log.i("IIAB-Clone", "recv state=" + st.phase + " title=" + st.title + " msg=" + st.message);
        if (st.seq > lastSeq) {
            if (st.phase == SyncTransferState.Phase.SUCCESS) { lastSeq = st.seq; showReceiveTerminal(true, st.message); }
            else if (st.phase == SyncTransferState.Phase.FAILED || st.phase == SyncTransferState.Phase.ABORTED) { lastSeq = st.seq; showReceiveTerminal(false, st.message); }
        }
        // ADFA-5155: on the success exit we WANT renderReceive — it draws step 2 with the exit status
        // zone (starting services / slow / countdown). The transfer state is idle by then.
        if (side == Side.RECEIVE) renderReceive();
        updateBackGuard();   // ADFA-5151: CONFIRM -> running (accepted) flips the Back confinement on
        syncProtection();   // ADFA-4782: match protection to the live pull state on every transition
    }

    /**
     * ADFA-5143: show or hide the transfer block, and let the Lottie follow it. The animation is only
     * ever running while the block that contains it is on screen — one place decides both, so a hidden
     * screen can't leave a loop spinning against the battery.
     */
    private void showProgress(boolean visible) {
        progressBox.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (anim == null || reduceMotion) return;
        if (visible) {
            if (!anim.isAnimating()) anim.playAnimation();
        } else if (anim.isAnimating()) {
            anim.pauseAnimation();
        }
    }

    /**
     * ADFA-5143: switch the M3 indicator between determinate and indeterminate without depending on
     * whether Material allows it in place. {@code BaseProgressIndicator} guards that transition while
     * the indicator is visible to the user, and this screen crosses it on every run (CONNECTING and
     * CALCULATING are indeterminate, TRANSFERRING is not) — so the bar is taken off screen for the
     * switch and put back. Costs nothing if the guard never fires.
     */
    private void setBarIndeterminate(boolean indeterminate) {
        if (pbar == null || pbar.isIndeterminate() == indeterminate) return;
        int was = pbar.getVisibility();
        pbar.setVisibility(View.GONE);
        pbar.setIndeterminate(indeterminate);
        pbar.setVisibility(was);
    }

    /**
     * ADFA-5151: enable the Back confinement only once the receive is accepted (running, past CONFIRM).
     * Before acceptance (JOIN / CONFIRM) Back is free; the user is still deciding. Reset the one-time
     * warning whenever it is off, so each accepted transfer warns once before backgrounding.
     */
    private void updateBackGuard() {
        if (receiveBackGate == null) return;
        SyncTransferState st = SyncProgressRepository.get().current();
        boolean accepted = side == Side.RECEIVE && st != null && st.isActive()
                && st.phase != SyncTransferState.Phase.CONFIRM;
        receiveBackGate.setEnabled(accepted);
        if (!accepted) leaveWarned = false;
    }

    private void renderReceive() {
        SyncTransferState st = SyncProgressRepository.get().current();
        boolean busy = (st != null && st.isActive());
        showProgress(busy);
        confirmPanel.setVisibility(View.GONE);
        rcvIncompat.setVisibility(View.GONE);   // ADFA-4784
        if (rcvExit != null && (busy || !exitInProgress)) rcvExit.setVisibility(View.GONE);   // ADFA-5155
        if (busy) {
            rcvSteps.setVisibility(View.GONE); rcvCaption.setVisibility(View.GONE);
            rcvIntro.setVisibility(View.GONE); rcvNotice.setVisibility(View.GONE);
            rcvScan.setVisibility(View.GONE); rcvSub.setVisibility(View.GONE);
            rcvSkip.setVisibility(View.GONE); rcvSkipHint.setVisibility(View.GONE);
            rcvCamNote.setVisibility(View.GONE); rcvShowPaste.setVisibility(View.GONE); pasteBlock.setVisibility(View.GONE);
            SyncTransferState.Phase ph = st.phase;
            if (ph == SyncTransferState.Phase.CONFIRM) {
                showProgress(false);
                // ADFA-4790: confirm as a System/Content/Total table (sizes travel in the QR) per the
                // design mockup; the replace notice is static in the layout. If the sender didn't send
                // sizes (older build), hide the table and just show the notice.
                SyncHandshakeHelper.SyncCredentials pc = syncVm.getPendingCreds();
                long sysB = (pc != null) ? pc.sysBytes : 0L;
                long contentB = (pc != null) ? pc.contentBytes : 0L;
                if (sysB > 0 || contentB > 0) {
                    confirmSys.setText(LibrarySize.human(sysB));
                    confirmContent.setText(LibrarySize.human(contentB));
                    confirmTotal.setText(LibrarySize.human(sysB + contentB));
                    confirmSizes.setVisibility(View.VISIBLE);
                } else {
                    confirmSizes.setVisibility(View.GONE);
                }
                // ADFA-4790: on an empty phone there's nothing to replace — show the benign notice
                // instead of the "replaces your library / no undo" warning.
                boolean fresh = !rootfsPresent();
                confirmFresh.setVisibility(fresh ? View.VISIBLE : View.GONE);
                confirmReplace.setVisibility(fresh ? View.GONE : View.VISIBLE);
                confirmPanel.setVisibility(View.VISIBLE);
                return;
            }
            if (ph == SyncTransferState.Phase.TRANSFERRING) {
                setBarIndeterminate(false);
                // setProgressCompat animates to the new value instead of jumping. The explicit switch
                // above stays: setProgressCompat can also leave indeterminate mode on its own, but only
                // after the current cycle, and that path isn't something this screen should depend on.
                pbar.setProgressCompat(st.percent, true);
                pStatus.setText(getString(R.string.k2go_clone_copying));
                pFile.setText(st.file);
                pStats.setText(st.percent + "%    " + st.speed + "    ETA " + st.eta);
            } else {
                setBarIndeterminate(true);
                pStatus.setText(ph == SyncTransferState.Phase.CALCULATING ? getString(R.string.k2go_clone_calculating) : getString(R.string.k2go_clone_connecting));
                pFile.setText(""); pStats.setText("");
            }
            return;
        }
        if (incompatHostBits >= 0) {   // ADFA-4784: not-compatible hard block, replaces the scan area
            rcvSteps.setVisibility(View.GONE); rcvCaption.setVisibility(View.GONE);
            rcvIntro.setVisibility(View.GONE); rcvNotice.setVisibility(View.GONE);
            rcvScan.setVisibility(View.GONE); rcvSub.setVisibility(View.GONE);
            rcvSkip.setVisibility(View.GONE); rcvSkipHint.setVisibility(View.GONE);
            rcvCamNote.setVisibility(View.GONE); rcvShowPaste.setVisibility(View.GONE); pasteBlock.setVisibility(View.GONE);
            rcvIncompat.setVisibility(View.VISIBLE);
            incompatWhyText.setVisibility(incompatWhyOpen ? View.VISIBLE : View.GONE);
            incompatWhy.setText(R.string.k2go_clone_why_incompat);
            setExpandChevron(incompatWhy, incompatWhyOpen, R.color.k2go_warn_ink);
            incompatTechText.setVisibility(incompatTechOpen ? View.VISIBLE : View.GONE);
            incompatTech.setText(R.string.k2go_clone_technical_details);
            setExpandChevron(incompatTech, incompatTechOpen, R.color.k2go_muted);
            incompatTechText.setText(getString(R.string.k2go_clone_tech_arch, bitsLabel(archBits()), bitsLabel(incompatHostBits)));
            return;
        }
        buildReceiveSteps();
        boolean atJoin = (rStage == RStage.JOIN);
        rcvSteps.setVisibility(View.VISIBLE);
        rcvCaption.setVisibility(View.VISIBLE);
        rcvCaption.setText(atJoin ? getString(R.string.k2go_clone_rcv_join_caption)
                : getString(R.string.k2go_clone_rcv_scan_caption));
        rcvIntro.setVisibility(atJoin ? View.VISIBLE : View.GONE);
        rcvNotice.setVisibility(atJoin ? View.GONE : View.VISIBLE);
        rcvScan.setText(atJoin ? getString(R.string.k2go_clone_rcv_scan_wifi) : getString(R.string.k2go_clone_rcv_scan_start));
        rcvScan.setVisibility(View.VISIBLE);
        rcvSub.setText(getString(atJoin ? R.string.k2go_clone_step_1of2 : R.string.k2go_clone_step_2of2));
        rcvSub.setVisibility(View.VISIBLE);
        rcvSkip.setText(getString(R.string.k2go_clone_already_connected));
        rcvSkipHint.setText(getString(R.string.k2go_clone_cant_join_note));
        rcvSkip.setVisibility(atJoin ? View.VISIBLE : View.GONE);
        rcvSkipHint.setVisibility(atJoin ? View.VISIBLE : View.GONE);
        rcvCamNote.setVisibility(View.GONE);
        rcvShowPaste.setVisibility(atJoin ? View.GONE : View.VISIBLE);
        rcvShowPaste.setText(R.string.k2go_clone_scan_enter_text);
        setExpandChevron(rcvShowPaste, pasteExpanded, R.color.k2go_teal);
        pasteBlock.setVisibility((!atJoin && pasteExpanded) ? View.VISIBLE : View.GONE);
        renderExitZone();   // ADFA-5155: the success-exit status band at the bottom of step 2
    }

    /** ADFA-5304: Material 3 expand/collapse affordance — one trailing icon that toggles state
     *  (expand_more when closed, expand_less when open), tinted to the row's role colour. */
    private void setExpandChevron(android.widget.TextView tv, boolean open, int colorRes) {
        android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), open ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        if (d != null) {
            d = d.mutate();
            androidx.core.graphics.drawable.DrawableCompat.setTint(d,
                    androidx.core.content.ContextCompat.getColor(requireContext(), colorRes));
        }
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, d, null);
        tv.setCompoundDrawablePadding((int) (6 * getResources().getDisplayMetrics().density));
    }

    // ---------------------------------------------------------- ADFA-5155: success-exit service wait

    /** The status band under step 2: "starting services…" -> (if slow) offer a re-scan -> "Opening…" countdown. */
    private void renderExitZone() {
        if (rcvExit == null) return;
        if (!exitInProgress || !exitAck) { rcvExit.setVisibility(View.GONE); if (exitDots != null) exitDots.stop(); return; }
        rcvExit.setVisibility(View.VISIBLE);
        if (exitCountingDown) {
            if (exitDots != null) exitDots.stop();
            rcvExitStatus.setText(getString(R.string.k2go_clone_exit_redirecting, exitSecs));
            rcvExitRescan.setVisibility(View.GONE);
            rcvExitWait.setVisibility(View.GONE);
            return;
        }
        boolean slow = !exitKeepWaiting && (android.os.SystemClock.elapsedRealtime() - exitStartAt > EXIT_SLOW_MS);
        if (slow) {
            if (exitDots != null) exitDots.stop();
            rcvExitStatus.setText(getString(R.string.k2go_clone_exit_slow));
            rcvExitRescan.setVisibility(View.VISIBLE);
            rcvExitWait.setVisibility(View.VISIBLE);
        } else {
            rcvExitRescan.setVisibility(View.GONE);
            rcvExitWait.setVisibility(View.GONE);
            if (exitDots != null) exitDots.start(getString(R.string.k2go_clone_starting_service));
        }
    }

    private void startExitPoll() {
        exitHandler.removeCallbacks(exitPollRunnable);
        exitHandler.post(exitPollRunnable);
    }

    // Probe the REST core off the main thread (apiReady can block ~5s), then reschedule from the callback
    // — the SetupProgressActivity pattern. Stops the moment services answer; never a hard failure deadline.
    private final Runnable exitPollRunnable = new Runnable() {
        @Override public void run() {
            if (!isAdded() || !exitInProgress || exitServicesUp || exitProbing) return;
            exitProbing = true;
            org.appdevforall.k2go.util.AppExecutors.get().io().execute(() -> {
                final boolean up = RestReadiness.apiReady();
                exitHandler.post(() -> {
                    exitProbing = false;
                    if (!isAdded() || !exitInProgress) return;
                    if (up) {
                        exitServicesUp = true;
                        maybeStartCountdown();
                        if (side == Side.RECEIVE) renderReceive();
                    } else {
                        if (side == Side.RECEIVE) renderReceive();   // may cross the "slow" threshold
                        exitHandler.postDelayed(exitPollRunnable, EXIT_POLL_MS);
                    }
                });
            });
        }
    };

    /** Begin the 3-2-1 only when BOTH facts hold: the user acknowledged and the services are truly up. */
    private void maybeStartCountdown() {
        if (!exitInProgress || !exitAck || !exitServicesUp || exitCountingDown) return;
        exitCountingDown = true;
        exitSecs = EXIT_REDIRECT_SECS;
        exitHandler.post(exitTick);
    }

    private final Runnable exitTick = new Runnable() {
        @Override public void run() {
            if (!isAdded() || !exitInProgress) return;
            if (exitSecs <= 0) { finishExit(); return; }
            if (side == Side.RECEIVE) renderReceive();   // shows "Opening your library in Ns…"
            exitSecs--;
            exitHandler.postDelayed(exitTick, 1000L);
        }
    };

    private void finishExit() {
        exitInProgress = false; exitCountingDown = false;
        exitHandler.removeCallbacks(exitTick);
        exitHandler.removeCallbacks(exitPollRunnable);
        if (exitDots != null) exitDots.stop();
        if (getActivity() instanceof LibraryActivity) ((LibraryActivity) getActivity()).openLibraryTab();
    }

    /** Services slow -> the user chooses to re-verify from the source. Non-destructive: rsync resumes and
     *  fills any gap. Tear down the wait and re-enter the normal receive scan (step 2). */
    private void exitRescan() {
        cancelExit();
        rStage = RStage.START;
        renderReceive();
        launchScanner(getString(R.string.k2go_clone_scan_prompt_receive));
    }

    /** Drop the exit wait entirely (re-scan, or the user navigated away). No fixed timer to leak. */
    private void cancelExit() {
        exitInProgress = false; exitAck = false; exitServicesUp = false;
        exitCountingDown = false; exitKeepWaiting = false; exitProbing = false;
        exitHandler.removeCallbacks(exitTick);
        exitHandler.removeCallbacks(exitPollRunnable);
        if (exitDots != null) exitDots.stop();
        if (rcvExit != null) rcvExit.setVisibility(View.GONE);
    }

    private void buildReceiveSteps() {
        rcvSteps.removeAllViews();
        boolean atStart = (rStage == RStage.START);
        rcvSteps.addView(badge("1", getString(R.string.k2go_badge_join), !atStart, atStart));
        rcvSteps.addView(arrow());
        rcvSteps.addView(badge("2", getString(R.string.k2go_badge_start), atStart, false));
    }

    private void launchScanner(String prompt) {
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt(prompt);
        o.setBeepEnabled(false);
        o.setBarcodeImageEnabled(false);
        o.setOrientationLocked(false);
        barcodeLauncher.launch(o);
    }

    private void onScan(String contents) {
        if (!isAdded()) return;
        if (contents == null) { Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_scan_cancelled), Toast.LENGTH_SHORT).show(); return; }
        SyncHandshakeHelper.SyncCredentials creds = SyncHandshakeHelper.parsePayload(contents);
        if (creds == null) { Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_scan_invalid), Toast.LENGTH_LONG).show(); return; }
        Log.i("IIAB-Clone", "scanned payload host=" + creds.ip + ":" + creds.port + " user=" + creds.user + " rootfs=" + creds.hasRootfs + " arch=" + creds.archBits);
        if (!archCompatible(creds.archBits)) { showIncompat(creds.archBits); return; }   // ADFA-4784
        probeOrWarnEmpty(creds);   // ADFA-4786
    }

    private void openWifiSettings() {
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_open_wifi), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startReceiveTransfer() {
        final Context app = requireContext().getApplicationContext();
        SyncHandshakeHelper.SyncCredentials creds = syncVm.getPendingCreds();
        File dest = syncVm.getPendingDestDir();
        if (creds == null || dest == null) {
            Toast.makeText(requireContext(), getString(R.string.k2go_clone_toast_expired), Toast.LENGTH_LONG).show();
            syncVm.releaseNetwork();
            SyncProgressRepository.get().postIdle();
            renderReceive();
            return;
        }
        // ADFA-4956: pulling overwrites the live rootfs — destructive like Restore. Hold the deep-env
        // lock + InstallGuard (recover a killed extract) and stop the server, THEN pull into a static
        // tree. releaseCloneEnv() on the terminal state boots the replaced system back.
        EnvironmentLock.acquire(app, EnvironmentLock.Owner.CLONE);
        cloneLockHeld = true;
        org.appdevforall.k2go.InstallGuard.begin(app);
        cloneGuardHeld = true;
        SyncProgressRepository.get().postTransferring(0, "", "", "RootFS");
        final String destPath = dest.getAbsolutePath();
        final SyncHandshakeHelper.SyncCredentials fcreds = creds;
        final Runnable pull = () -> {
            // ADFA-5070: the rootfs arriving from the other device replaces this one.
            // Done here rather than at the tap: this runs off the main thread, and
            // the invalidator touches SharedPreferences. Sessions only — a transfer
            // that dies on the first byte leaves the system intact, so the pending
            // orders are not discarded until it actually completes.
            org.appdevforall.k2go.system.data.ContentStateInvalidator.replacementStarting(app,
                    org.appdevforall.k2go.system.domain.SystemReplacement.Cause.CLONE_RECEIVE);
            // ADFA-5160: anchor the progress bar to the dry-run's bytes-to-transfer (startProbe
            // ran it before this point), i.e. what rsync computed for THIS transfer. Not the QR
            // estimate: it reflects the sender's initial install and can be stale. 0 falls back
            // to rsync's own percent.
            long expectedTotal = syncVm.getPendingBytes();
            transport.startClient(app, shareConfig, fcreds.ip, fcreds.port, fcreds.user, fcreds.pass, destPath, expectedTotal,
                new TransportEngine.SyncListener() {
                    @Override public void onProgress(int pct, String speed, String eta, String file) { SyncProgressRepository.get().postTransferring(pct, speed, eta, file); }
                    @Override public void onComplete(String message) {
                        // The local rootfs is now the other device's, so anything this
                        // one had pending was placed against a system that is gone.
                        org.appdevforall.k2go.system.data.ContentStateInvalidator.replacementSucceeded(app,
                                org.appdevforall.k2go.system.domain.SystemReplacement.Cause.CLONE_RECEIVE);
                        SyncProgressRepository.get().postSuccess(message);
                    }
                    @Override public void onError(String error) { SyncProgressRepository.get().postFailed(error); }
                });
        };
        ServerController sc = server();
        if (sc != null) sc.stopEnvironment(pull); else pull.run();
    }

    private void showReceiveTerminal(boolean ok, String message) {
        // ADFA-5143: reaching a receive terminal is itself the proof that this side owns the clone
        // environment — whatever this fragment instance happens to remember. cloneLockHeld and
        // cloneGuardHeld are fragment fields, so a fragment recreated mid-transfer (app closed and
        // reopened, then re-attached to the running rsync) arrived here with both false and
        // releaseCloneEnv() returned on its first line: the install marker stayed set, the lock was
        // never dropped and the server was never booted. The next launch then read that leftover
        // marker as a damaged install — of a clone that had in fact completed. Re-derive the two
        // facts from disk, where they actually live, instead of trusting the fragment's memory.
        Context termCtx = getContext();
        if (termCtx != null) {
            cloneLockHeld = true;   // EnvironmentLock.release() is idempotent and self-heals a stale file
            cloneGuardHeld = org.appdevforall.k2go.InstallGuard.isLive(termCtx);   // ADFA-5343 (Phase 5a): our own live marker, this process
        }
        releaseCloneEnv();   // ADFA-4956: boot the (possibly replaced) system, end guard, drop the lock
        syncVm.releaseNetwork();

        if (ok) {
            // ADFA-5155: success exit. releaseCloneEnv() above already booted the environment; start
            // probing the REST core now (background) and confirm with a pop-up. The OK does NOT navigate —
            // it lands the user on receive step 2, where the exit status zone waits until apiReady answers
            // before counting down and redirecting. Never a fixed timer: a slow boot must not drop onto a
            // dead Home, and 45s must never condemn a long, good transfer.
            exitInProgress = true; exitAck = false; exitServicesUp = false;
            exitKeepWaiting = false; exitCountingDown = false;
            exitStartAt = android.os.SystemClock.elapsedRealtime();
            SyncProgressRepository.get().postIdle();   // the copy is done; drop the busy/progress state
            startExitPoll();
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.k2go_clone_copy_complete))
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        exitAck = true;
                        rStage = RStage.START;   // land on step 2, where the status zone lives
                        maybeStartCountdown();   // if services already answered, begin the 3-2-1 now
                        if (side == Side.RECEIVE) renderReceive();
                    })
                    .show();
            return;
        }

        String body = (message != null) ? message : "";
        // ADFA-4782: if rsync was SIGKILLed (exit 137, phantom-process killer), guide the user.
        if (body.contains("137")) {
            body += "\n\nThe copy was stopped by the system. Keep this screen on and the app in the "
                  + "foreground during a transfer, then scan again to resume.";
        }
        // ADFA-5151: fail -> the user chooses, never stranded and never dropped onto a systemless Home.
        // Retry re-scans (rsync resumes from the half-written tree — the maravilla Luis relies on);
        // Recover goes to the hub (restore / install over internet / clone again). The Back guard has
        // already turned off (FAILED is not active), so leaving the app is free too.
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.k2go_clone_copy_stopped))
                .setMessage(body)
                .setPositiveButton(getString(R.string.k2go_home_retry), (d, w) -> { SyncProgressRepository.get().postIdle(); renderReceive(); })
                .setNegativeButton(getString(R.string.k2go_home_recover), (d, w) -> {
                    SyncProgressRepository.get().postIdle();
                    SetupLibraryActivity.recover(requireContext());
                })
                .setCancelable(false)
                .show();
    }

    // ADFA-5143: the loop follows the screen, like the backup / restore job screen. Only the animation
    // is touched here — the transfer itself is owned by the service and keeps running either way.
    @Override
    public void onResume() {
        super.onResume();
        // Re-read the setting here rather than trusting what onCreateView saw: changing it doesn't
        // recreate the fragment, so a cached answer can outlive the truth. Coming back to the front is
        // the only moment it can have changed behind our back, and it keeps the read off the tick path.
        reduceMotion = org.appdevforall.k2go.util.Motion.reduced(getContext());
        if (anim != null && reduceMotion && anim.isAnimating()) anim.pauseAnimation();
        if (progressBox != null) showProgress(progressBox.getVisibility() == View.VISIBLE);
        // ADFA-5150: a system may have been recovered while away (Send's Recover sends them off and
        // back). Refresh so the Send empty state clears once a system exists, and redraw if on Send.
        systemPresent = org.appdevforall.k2go.SystemStateEvaluator.isSystemInstalled(requireContext());
        if (!atFork && side == Side.SEND) render();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (anim != null && anim.isAnimating()) anim.pauseAnimation();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (startingDots != null) startingDots.stop();   // ADFA-5154: no dangling Handler after the view is gone
        // ADFA-5155: tear down the success-exit wait so no probe/countdown Handler outlives the view.
        exitHandler.removeCallbacks(exitTick);
        exitHandler.removeCallbacks(exitPollRunnable);
        if (exitDots != null) exitDots.stop();
        // K2GO-375: stop any "resolving…" ellipsis so its Handler cannot tick a destroyed TextView (the
        // former netHandler AP-IP poll is gone — the hotspot owner now drives the redraw).
        if (secJoin != null) secJoin.stopPending();
        if (secGetApp != null) secGetApp.stopPending();
        // ADFA-4782: release protection only when nothing is running; an active share daemon or pull
        // keeps the (app-scoped) CloneShareService alive so leaving the tab doesn't cut the transfer.
        // ADFA-4956: same gate for the deep-env lock — only boot the server back + drop the lock when
        // nothing is in flight; an ongoing share/pull must keep the server down until it ends.
        if (!SyncProgressRepository.get().isActive() && !daemonStarted) { stopProtection(); releaseCloneEnv(); }
        if (!SyncProgressRepository.get().isActive()) syncVm.releaseNetwork();
        stopApkServer();   // ADFA-4785
    }

    private void confirmStop() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.k2go_clone_stopshare_title))
                .setMessage(getString(R.string.k2go_clone_stopshare_msg))
                .setNegativeButton(getString(R.string.k2go_cancel), null)
                .setPositiveButton(getString(R.string.k2go_clone_stop_confirm), (d, w) -> {
                    if (transport != null) transport.stop();
                    daemonStarted = false;
                    userStopped = true;   // do not auto-restart on the next render
                    CloneSendSession.clear();   // ADFA-4960: the send session ended
                    releaseCloneEnv();    // ADFA-4956: boot the server back + drop the deep-env lock
                    render();
                })
                .show();
    }

    private void simpleState(String cap, String sub) {
        qr.setImageBitmap(null);
        caption.setText(cap);
        subCaption.setText(sub);
        advance.setVisibility(View.GONE);
        actionFooter.setVisibility(View.GONE);
        footer.setText("");
    }

    private void showCodeAsText(String payload) {
        currentPayload = payload;
        codetext.setText(payload);
        // ADFA-5236: fixed label + always-visible code block (the reveal toggle is gone).
        showcode.setText(R.string.k2go_clone_scan_show_text);
        showcode.setVisibility(View.VISIBLE);
        codeblock.setVisibility(View.VISIBLE);
    }

    // ---- step badges (same style as Connect). ADFA-5154: Page 1 (Prepare) lights ① and ② together;
    // Page 2 (Copy) lights ③ and marks ①·② done. ----
    private void buildSteps() {
        steps.removeAllViews();
        steps.setVisibility(View.VISIBLE);
        boolean copy = (page == Page.COPY);
        steps.addView(badge("1", getString(R.string.k2go_badge_join), !copy, copy));
        steps.addView(arrow());
        steps.addView(badge("2", getString(R.string.k2go_badge_getapp), !copy, copy));
        steps.addView(arrow());
        steps.addView(badge("3", getString(R.string.k2go_badge_copy), copy, false));
    }

    private View badge(String num, String label, boolean active, boolean done) {
        Context ctx = requireContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout fl = new FrameLayout(ctx);
        int d = dp(38);
        boolean filled = active || done;
        View circle = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (filled) g.setColor(ContextCompat.getColor(ctx, R.color.k2go_teal));
        else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), ContextCompat.getColor(ctx, R.color.k2go_muted)); }
        circle.setBackground(g);
        fl.addView(circle, new FrameLayout.LayoutParams(d, d));

        TextView t = new TextView(ctx);
        t.setText(num);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ContextCompat.getColor(ctx, filled ? R.color.k2go_on_teal : R.color.k2go_muted));
        fl.addView(t, new FrameLayout.LayoutParams(d, d));

        if (done) {
            FrameLayout check = new FrameLayout(ctx);
            int cd = dp(16);
            View co = new View(ctx);
            GradientDrawable cg = new GradientDrawable();
            cg.setShape(GradientDrawable.OVAL);
            cg.setColor(ContextCompat.getColor(ctx, R.color.k2go_leaf));
            co.setBackground(cg);
            check.addView(co, new FrameLayout.LayoutParams(cd, cd));
            TextView ck = new TextView(ctx);
            ck.setText("✓");
            ck.setGravity(Gravity.CENTER);
            ck.setTextSize(9);
            ck.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_on_teal));
            check.addView(ck, new FrameLayout.LayoutParams(cd, cd));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cd, cd);
            clp.gravity = Gravity.TOP | Gravity.END;
            fl.addView(check, clp);
        }
        col.addView(fl, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setGravity(Gravity.CENTER);
        lbl.setTextSize(12);
        lbl.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        col.addView(lbl);
        return col;
    }

    private View arrow() {
        TextView a = new TextView(requireContext());
        a.setText("→");
        a.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        a.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.bottomMargin = dp(18);
        a.setLayoutParams(lp);
        return a;
    }

    private void paintTab(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.k2go_primary_bg : 0);
        t.setTextColor(ContextCompat.getColor(requireContext(), on ? R.color.k2go_on_teal : R.color.k2go_muted));
    }

    private void styleAdvance(boolean filled) {
        // ADFA-5346: shape/size come from the style; only toggle the fill + label emphasis by state.
        K2GoButtons.setFilledEmphasis(advance, filled);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
