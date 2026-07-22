package org.iiab.controller.redesign;

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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import org.iiab.controller.ApkServer;
import org.iiab.controller.BuildConfig;
import org.iiab.controller.R;
import org.iiab.controller.SyncHandshakeHelper;
import org.iiab.controller.WatchdogService;
import org.iiab.controller.sync.domain.ApkShareName;
import org.iiab.controller.hotspot.LocalHotspotManager;
import org.iiab.controller.sync.domain.ShareConfig;
import org.iiab.controller.sync.presentation.SyncProgressRepository;
import org.iiab.controller.sync.presentation.SyncStateViewModel;
import org.iiab.controller.sync.presentation.SyncTransferState;
import org.iiab.controller.sync.transport.NetworkInterfaces;
import org.iiab.controller.sync.transport.QrCodec;
import org.iiab.controller.sync.transport.TransportEngine;

/**
 * Clone tab — Send side (ADFA-4777): copy the whole system to another device. Two-code flow per
 * the final design (Join → Start), reusing the existing rsync transfer backend (TransportEngine +
 * SyncHandshakeHelper), the LocalOnly hotspot, and QrCodec. "Stop sharing" is Clone-only (the copy
 * is heavy and stoppable). Receive lands in a follow-up PR under the same ticket.
 */
public class CloneFragment extends Fragment {

    private enum Side { SEND, RECEIVE }
    private enum Mode { HOTSPOT, WIFI }
    private enum Stage { JOIN, START }

    private Side side = Side.SEND;
    private Mode mode = Mode.HOTSPOT;
    private Stage stage = Stage.JOIN;
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
    private boolean shareAnyway = false;  // ADFA-4786: user chose to share even with no library installed
    // ADFA-4785: "Send the app" sub-screen (bootstrap a phone with no K2Go) — serves the APK over HTTP.
    private boolean sendApp = false;
    private ApkServer apkServer;
    private String apkFileName;
    private LinearLayout sendAppEntry, sendAppView;
    private ImageView sendAppQr;

    private ActivityResultLauncher<String> locationPerm;

    private TextView tabSend, tabReceive, tabHotspot, tabWifi, caption, subCaption, advance, stop, footer;
    // ADFA-4785: intent fork (Send / Receive) replaces the persistent top toggle.
    private boolean atFork = true;
    private LinearLayout forkBox, tabsRow;
    private TextView cloneHdr, subtitleView, backHeader;
    private boolean getAppSkipped = false, getAppDone = false;   // ADFA-4785: step-2 (Get app) disposition
    private TextView stepTitle, skipApp, shareWifi;
    // Receive side
    private SyncStateViewModel syncVm;
    private LinearLayout receiveBox, progressBox;
    private EditText paste;
    private TextView receiveStart, pStatus, pFile, pStats, cancel;
    private ProgressBar pbar;
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
    private LinearLayout netRow, steps, fallback, fallbackValues;
    private ImageView qr;
    private TextView showcode, codetext, copyBtn, shareBtn;
    private LinearLayout codeblock;
    private String currentPayload = "";
    private boolean codeExpanded = false;

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
        fallback = v.findViewById(R.id.k2go_clone_fallback);
        fallbackValues = v.findViewById(R.id.k2go_clone_fallback_values);
        advance = v.findViewById(R.id.k2go_clone_advance);
        stop = v.findViewById(R.id.k2go_clone_stop);
        footer = v.findViewById(R.id.k2go_clone_footer);
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
        pStatus = v.findViewById(R.id.k2go_clone_pstatus);
        pbar = v.findViewById(R.id.k2go_clone_pbar);
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
        sendAppEntry = v.findViewById(R.id.k2go_sendapp_entry);
        sendAppView = v.findViewById(R.id.k2go_sendapp_view);
        sendAppQr = v.findViewById(R.id.k2go_sendapp_qr);
        sendAppEntry.setOnClickListener(x -> { sendApp = true; render(); });
        v.findViewById(R.id.k2go_sendapp_back).setOnClickListener(x -> exitSendApp());
        v.findViewById(R.id.k2go_sendapp_next).setOnClickListener(x -> getAppDoneNext());
        v.findViewById(R.id.k2go_sendapp_share).setOnClickListener(x -> shareApkViaSheet());

        syncVm = new ViewModelProvider(requireActivity()).get(SyncStateViewModel.class);
        transport = syncVm.getTransport();
        shareConfig = ShareConfig.defaults();

        tabSend.setOnClickListener(x -> setSide(Side.SEND));
        tabReceive.setOnClickListener(x -> setSide(Side.RECEIVE));
        tabHotspot.setOnClickListener(x -> requestMode(Mode.HOTSPOT));
        tabWifi.setOnClickListener(x -> requestMode(Mode.WIFI));
        advance.setOnClickListener(x -> {
            if (stage == Stage.JOIN) { sendApp = true; render(); }        // step 1 -> step 2 (Get app)
            else { stage = Stage.JOIN; getAppDone = false; render(); }     // step 3 -> back to step 1
        });
        showcode.setOnClickListener(x -> {
            codeExpanded = !codeExpanded;
            codeblock.setVisibility(codeExpanded ? View.VISIBLE : View.GONE);
            showcode.setText(codeExpanded ? getString(R.string.k2go_clone_hide_code) : getString(R.string.k2go_clone_scan_show_text));
        });
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
        SyncTransferState cur = SyncProgressRepository.get().current();
        lastSeq = (cur != null) ? cur.seq : -1L;   // only fire dialogs on NEW transitions
        SyncProgressRepository.get().state().observe(getViewLifecycleOwner(), this::onTransferState);

        cloneHdr = v.findViewById(R.id.k2go_clone_hdr);
        subtitleView = v.findViewById(R.id.k2go_clone_subtitle);
        backHeader = v.findViewById(R.id.k2go_clone_back);
        forkBox = v.findViewById(R.id.k2go_clone_fork);
        tabsRow = v.findViewById(R.id.k2go_clone_tabs);
        stepTitle = v.findViewById(R.id.k2go_clone_steptitle);
        skipApp = v.findViewById(R.id.k2go_clone_skipapp);
        skipApp.setOnClickListener(x -> { getAppSkipped = true; stage = Stage.START; render(); });
        shareWifi = v.findViewById(R.id.k2go_clone_sharewifi);
        shareWifi.setOnClickListener(x -> openWifiSettings());
        v.findViewById(R.id.k2go_clone_fork_send).setOnClickListener(x -> enterSide(Side.SEND));
        v.findViewById(R.id.k2go_clone_fork_receive).setOnClickListener(x -> enterSide(Side.RECEIVE));
        backHeader.setOnClickListener(x -> goToFork());
        render();
        return v;
    }

    private void enterSide(Side sd) { atFork = false; getAppSkipped = false; getAppDone = false; sendApp = false; setSide(sd); }

    private void goToFork() { atFork = true; render(); }

    private void setSide(Side sd) {
        side = sd;
        if (sd == Side.SEND) { stage = Stage.JOIN; setMode(Mode.HOTSPOT); return; }   // ADFA-4785: enter Send at step 1
        rStage = RStage.JOIN; pasteExpanded = false;
        incompatHostBits = -1; incompatWhyOpen = false; incompatTechOpen = false;   // ADFA-4784: fresh on entry
        render();
    }

    // ADFA-4785: switching the network at step 3 (Copy) drops the active connection and cuts any
    // copy in progress. Warn first; elsewhere (or when the mode is unchanged) switch directly.
    private void requestMode(Mode target) {
        boolean sharing = (side == Side.SEND && !sendApp && stage == Stage.START && daemonStarted);
        if (target == mode || !sharing) { setMode(target); return; }
        String label = (target == Mode.HOTSPOT) ? "Hotspot" : "Wi-Fi";
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.k2go_clone_switch_title, label))
                .setMessage(getString(R.string.k2go_clone_switch_msg))
                .setNegativeButton(getString(R.string.k2go_cancel), null)
                .setPositiveButton(getString(R.string.k2go_clone_switch_confirm), (d, w) -> setMode(target))
                .show();
    }

    private void setMode(Mode m) {
        mode = m;
        if (m == Mode.HOTSPOT) ensureHotspot();
        render();   // ADFA-4785: keep the current step; switching Hotspot/Wi-Fi no longer resets to step 1
    }

    private void ensureHotspot() {
        if (!LocalHotspotManager.isSupported() || hs.isOn()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            hs.start(requireContext().getApplicationContext());
        } else {
            locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    /** Start the rsync share daemon once we have a LAN IP (off the main thread). */
    private void ensureDaemon(String ip) {
        if (daemonStarted || daemonStarting || userStopped || ip == null) return;
        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        boolean hasLib = rootfsPresent();
        if (!hasLib && !shareAnyway) return;   // ADFA-4786: don't silently share an empty library; renderStartState shows the notice
        daemonStarting = true;
        tempPass = SyncHandshakeHelper.generateSecurePassword();
        hostHasRootfs = hasLib;
        if (!rootfsDir.exists()) rootfsDir.mkdirs();
        final String shareDir = rootfsDir.getAbsolutePath();
        final Context app = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity act = requireActivity();  // capture before the thread
        final File iiabRoot = rootfsDir;  // effectively final for the worker
        new Thread(() -> {
            final boolean ok = transport.startServer(app, shareConfig, tempPass, shareDir);
            final LibrarySize.Split split = LibrarySize.compute(iiabRoot);  // ADFA-4780: approx sizes for the QR + overview
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                daemonStarting = false; daemonStarted = ok; librarySplit = split; render();
            });
        }, "clone-share-daemon").start();
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
     * follows the app's install ABI, not the hardware. Mirrors ArchCheckController.getArchBits().
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
        Intent i = new Intent(ctx, WatchdogService.class).setAction(WatchdogService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
        protectionOn = true;
        Log.i("IIAB-Clone", "watchdog protection ON");
    }

    private void stopProtection() {
        if (!protectionOn) return;
        protectionOn = false;
        Context ctx = getContext();
        if (ctx == null) return;   // detached; the service is app-scoped and stops on its own teardown path
        ctx.startService(new Intent(ctx, WatchdogService.class).setAction(WatchdogService.ACTION_STOP));
        Log.i("IIAB-Clone", "watchdog protection OFF");
    }

    private void render() {
        if (!isAdded() || caption == null) return;
        if (showcode != null) { showcode.setVisibility(View.GONE); codeblock.setVisibility(View.GONE); }
        if (stepTitle != null) { stepTitle.setVisibility(View.GONE); skipApp.setVisibility(View.GONE); shareWifi.setVisibility(View.GONE); }
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
            qr.setVisibility(View.GONE);
            caption.setVisibility(View.GONE);
            subCaption.setVisibility(View.GONE);
            fallback.setVisibility(View.GONE);
            advance.setVisibility(View.GONE);
            stop.setVisibility(View.GONE);
            footer.setVisibility(View.GONE);
            shareCard.setVisibility(View.GONE);
            sendAppEntry.setVisibility(View.GONE);
            sendAppView.setVisibility(View.GONE);
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
            qr.setVisibility(View.GONE);
            caption.setVisibility(View.GONE);
            subCaption.setVisibility(View.GONE);
            fallback.setVisibility(View.GONE);
            advance.setVisibility(View.GONE);
            stop.setVisibility(View.GONE);
            footer.setVisibility(View.GONE);
            shareCard.setVisibility(View.GONE);
            sendAppEntry.setVisibility(View.GONE);
            sendAppView.setVisibility(View.GONE);
            receiveBox.setVisibility(View.VISIBLE);
            renderReceive();
            syncProtection();
            return;
        }
        receiveBox.setVisibility(View.GONE);
        if (sendApp) {   // ADFA-4785: step 2 (Get app) — spine + step title + network selector, then the sub-view
            netRow.setVisibility(View.VISIBLE); qr.setVisibility(View.GONE);
            paintTab(tabHotspot, mode == Mode.HOTSPOT);
            paintTab(tabWifi, mode == Mode.WIFI);
            caption.setVisibility(View.GONE); subCaption.setVisibility(View.GONE); footer.setVisibility(View.GONE);
            fallback.setVisibility(View.GONE); advance.setVisibility(View.GONE); stop.setVisibility(View.GONE);
            shareCard.setVisibility(View.GONE); sendAppEntry.setVisibility(View.GONE);
            steps.setVisibility(View.VISIBLE); buildSteps(true);
            stepTitle.setVisibility(View.VISIBLE); stepTitle.setText(getString(R.string.k2go_clone_step2_title));
            sendAppView.setVisibility(View.VISIBLE);
            renderSendApp();
            return;
        }
        sendAppView.setVisibility(View.GONE);
        sendAppEntry.setVisibility(View.GONE);   // renderHotspot's JOIN stage re-shows it
        netRow.setVisibility(View.VISIBLE);
        qr.setVisibility(View.VISIBLE);
        caption.setVisibility(View.VISIBLE);
        subCaption.setVisibility(View.VISIBLE);
        footer.setVisibility(View.VISIBLE);   // RECEIVE hid it; restore for SEND
        stop.setVisibility(View.GONE);
        shareCard.setVisibility(View.GONE);
        paintTab(tabHotspot, mode == Mode.HOTSPOT);
        paintTab(tabWifi, mode == Mode.WIFI);

        if (mode == Mode.HOTSPOT) renderHotspot(); else renderWifi();
        syncProtection();
    }

    private void renderHotspot() {
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = (st != null) ? st.phase : LocalHotspotManager.Phase.OFF;
        if (!LocalHotspotManager.isSupported()) { simpleState(getString(R.string.k2go_connect_hotspot_unsupported), getString(R.string.k2go_connect_try_wifi)); return; }
        if (phase == LocalHotspotManager.Phase.OFF || phase == LocalHotspotManager.Phase.STARTING) { simpleState(getString(R.string.k2go_connect_starting_hotspot), ""); return; }
        if (phase == LocalHotspotManager.Phase.FAILED) { simpleState(getString(R.string.k2go_connect_hotspot_failed), getString(R.string.k2go_connect_enable_location)); return; }

        String ssid = (st.ssid != null) ? st.ssid : "";
        String pass = (st.passphrase != null) ? st.passphrase : "";
        String ip = NetworkInterfaces.discover().hotspotIp;
        if (ip == null) ip = "192.168.49.1";

        buildSteps(true);
        advance.setVisibility(View.VISIBLE);
        if (stage == Stage.JOIN) {
            setQr("WIFI:S:" + ssid + ";T:WPA;P:" + pass + ";;");
            stepTitle.setVisibility(View.VISIBLE);
            stepTitle.setText(getString(R.string.k2go_clone_step1_title));
            caption.setText(getString(R.string.k2go_clone_point_camera_join));
            subCaption.setText("");
            setFallback(new String[]{getString(R.string.k2go_fallback_wifi, ssid), getString(R.string.k2go_fallback_pass, pass)});
            advance.setText(getString(R.string.k2go_clone_next_get_app));
            styleAdvance(true);
            footer.setText("");
            sendAppEntry.setVisibility(View.GONE);
            skipApp.setVisibility(View.VISIBLE);
            skipApp.setText(getString(R.string.k2go_clone_skip_have_app));
        } else {
            advance.setText(getString(R.string.k2go_clone_back_step1));
            styleAdvance(false);
            ensureDaemon(ip);
            renderStartState(ip, true);
        }
    }

    private void renderWifi() {
        String ip = NetworkInterfaces.discover().wifiIp;
        if (ip == null) { buildSteps(false); advance.setVisibility(View.GONE); simpleState(getString(R.string.k2go_connect_no_wifi), getString(R.string.k2go_connect_join_wifi)); return; }
        buildSteps(false);
        if (stage == Stage.JOIN) {
            qr.setVisibility(View.GONE);
            stepTitle.setVisibility(View.VISIBLE);
            stepTitle.setText(getString(R.string.k2go_clone_step1_title));
            caption.setText(getString(R.string.k2go_clone_join_wifi_note));
            subCaption.setText(getString(R.string.k2go_clone_share_wifi_note));
            setFallback(null);
            footer.setText("");
            advance.setVisibility(View.VISIBLE);
            shareWifi.setVisibility(View.VISIBLE);
            advance.setText(getString(R.string.k2go_clone_next_get_app));
            styleAdvance(true);
            skipApp.setVisibility(View.VISIBLE);
            skipApp.setText(getString(R.string.k2go_clone_skip_have_app));
        } else {
            advance.setVisibility(View.VISIBLE);
            advance.setText(getString(R.string.k2go_clone_back_step1));
            styleAdvance(false);
            ensureDaemon(ip);
            renderStartState(ip, false);
        }
    }

    /** Step-2 state: starting -> stopped (Start sharing) -> running (QR + Stop sharing). */
    private void renderStartState(String ip, boolean twoCode) {
        stepTitle.setVisibility(View.VISIBLE);
        stepTitle.setText(getString(R.string.k2go_clone_step3_title));
        if (!daemonStarted && !daemonStarting && !shareAnyway && !rootfsPresent()) {   // ADFA-4786
            qr.setImageBitmap(null);
            caption.setText(getString(R.string.k2go_clone_nothing_title));
            subCaption.setText(getString(R.string.k2go_clone_no_library_note));
            setFallback(null); footer.setText(""); shareCard.setVisibility(View.GONE);
            stop.setVisibility(View.VISIBLE);
            stop.setText(getString(R.string.k2go_clone_share_anyway));
            stop.setBackgroundResource(R.drawable.k2go_getmore_bg);
            stop.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
            stop.setOnClickListener(x -> { shareAnyway = true; render(); });
            return;
        }
        if (daemonStarting) {
            qr.setImageBitmap(null);
            caption.setText(getString(R.string.k2go_clone_starting_service));
            subCaption.setText("");
            setFallback(null); footer.setText(""); stop.setVisibility(View.GONE); shareCard.setVisibility(View.GONE);
            return;
        }
        if (!daemonStarted) {   // stopped by the user (or failed to start)
            qr.setImageBitmap(null);
            caption.setText(getString(R.string.k2go_clone_sharing_stopped));
            subCaption.setText(getString(R.string.k2go_clone_start_service_note));
            setFallback(null); footer.setText(""); shareCard.setVisibility(View.GONE);
            showStartButton();
            return;
        }
        long sysB = (librarySplit != null) ? librarySplit.systemBytes : 0L;
        long contentB = (librarySplit != null) ? librarySplit.contentBytes : 0L;
        String payload = SyncHandshakeHelper.createPayload(ip, shareConfig.rsyncPort, shareConfig.user, tempPass, hostHasRootfs, archBits(), sysB, contentB);
        qr.setImageBitmap(SyncHandshakeHelper.generateQrCode(payload, 500));
        caption.setText(getString(twoCode ? R.string.k2go_clone_ready_scan2 : R.string.k2go_clone_ready_scan));
        subCaption.setText(getString(R.string.k2go_clone_copy_begins_note));
        setFallback(null);
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

    // ------------------------------------------------------------ "Send the app" (ADFA-4785)

    private void renderSendApp() {
        if (mode == Mode.HOTSPOT) ensureHotspot();
        startApkServer();
        NetworkInterfaces.LanIps net = NetworkInterfaces.discover();
        String ip = (mode == Mode.HOTSPOT) ? net.hotspotIp : net.wifiIp;
        if (ip == null || apkServer == null) { sendAppQr.setImageBitmap(null); return; }
        String url = "http://" + ip + ":" + shareConfig.apkPort + "/" + apkFileName;
        sendAppQr.setImageBitmap(SyncHandshakeHelper.generateQrCode(url, 500));
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

    private void getAppDoneNext() {   // ADFA-4785: step 2 (Get app) done -> step 3 (Copy)
        getAppDone = true;
        stage = Stage.START;
        stopApkServer();
        sendApp = false;
        render();
    }

    private void exitSendApp() {
        stopApkServer();
        sendApp = false;
        render();
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

    private void showStopButton() {
        stop.setVisibility(View.VISIBLE);
        stop.setText(getString(R.string.k2go_clone_stop_sharing));
        stop.setBackgroundResource(R.drawable.k2go_turnoff_bg);
        stop.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_clay));
        stop.setOnClickListener(x -> confirmStop());
    }

    private void showStartButton() {
        stop.setVisibility(View.VISIBLE);
        stop.setText(getString(R.string.k2go_clone_start_sharing));
        stop.setBackgroundResource(R.drawable.k2go_primary_bg);
        stop.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_on_teal));
        stop.setOnClickListener(x -> { userStopped = false; render(); });
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
            new AlertDialog.Builder(requireContext())
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
        if (side == Side.RECEIVE) renderReceive();
        syncProtection();   // ADFA-4782: match protection to the live pull state on every transition
    }

    private void renderReceive() {
        SyncTransferState st = SyncProgressRepository.get().current();
        boolean busy = (st != null && st.isActive());
        progressBox.setVisibility(busy ? View.VISIBLE : View.GONE);
        confirmPanel.setVisibility(View.GONE);
        rcvIncompat.setVisibility(View.GONE);   // ADFA-4784
        if (busy) {
            rcvSteps.setVisibility(View.GONE); rcvCaption.setVisibility(View.GONE);
            rcvIntro.setVisibility(View.GONE); rcvNotice.setVisibility(View.GONE);
            rcvScan.setVisibility(View.GONE); rcvSub.setVisibility(View.GONE);
            rcvSkip.setVisibility(View.GONE); rcvSkipHint.setVisibility(View.GONE);
            rcvCamNote.setVisibility(View.GONE); rcvShowPaste.setVisibility(View.GONE); pasteBlock.setVisibility(View.GONE);
            SyncTransferState.Phase ph = st.phase;
            if (ph == SyncTransferState.Phase.CONFIRM) {
                progressBox.setVisibility(View.GONE);
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
                pbar.setIndeterminate(false);
                pbar.setProgress(st.percent);
                pStatus.setText(getString(R.string.k2go_clone_copying));
                pFile.setText(st.file);
                pStats.setText(st.percent + "%    " + st.speed + "    ETA " + st.eta);
            } else {
                pbar.setIndeterminate(true);
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
            incompatWhy.setText(incompatWhyOpen ? getString(R.string.k2go_clone_why_incompat_open) : getString(R.string.k2go_clone_why_incompat));
            incompatTechText.setVisibility(incompatTechOpen ? View.VISIBLE : View.GONE);
            incompatTech.setText(incompatTechOpen ? getString(R.string.k2go_clone_technical_details_open) : getString(R.string.k2go_clone_technical_details));
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
        rcvShowPaste.setText(pasteExpanded ? getString(R.string.k2go_clone_enter_text_open) : getString(R.string.k2go_clone_scan_enter_text));
        pasteBlock.setVisibility((!atJoin && pasteExpanded) ? View.VISIBLE : View.GONE);
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
        SyncProgressRepository.get().postTransferring(0, "", "", "RootFS");
        transport.startClient(app, shareConfig, creds.ip, creds.port, creds.user, creds.pass, dest.getAbsolutePath(),
                new TransportEngine.SyncListener() {
                    @Override public void onProgress(int pct, String speed, String eta, String file) { SyncProgressRepository.get().postTransferring(pct, speed, eta, file); }
                    @Override public void onComplete(String message) { SyncProgressRepository.get().postSuccess(message); }
                    @Override public void onError(String error) { SyncProgressRepository.get().postFailed(error); }
                });
    }

    private void showReceiveTerminal(boolean ok, String message) {
        syncVm.releaseNetwork();
        String body = (message != null) ? message : "";
        // ADFA-4782: if rsync was SIGKILLed (exit 137, phantom-process killer), guide the user.
        if (!ok && body.contains("137")) {
            body += "\n\nThe copy was stopped by the system. Keep this screen on and the app in the "
                  + "foreground during a transfer, then scan again to resume.";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(ok ? R.string.k2go_clone_copy_complete : R.string.k2go_clone_copy_stopped))
                .setMessage(body)
                .setPositiveButton(getString(android.R.string.ok), (d, w) -> { SyncProgressRepository.get().postIdle(); renderReceive(); })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // ADFA-4782: release protection only when nothing is running; an active share daemon or pull
        // keeps the (app-scoped) WatchdogService alive so leaving the tab doesn't cut the transfer.
        if (!SyncProgressRepository.get().isActive() && !daemonStarted) stopProtection();
        if (!SyncProgressRepository.get().isActive()) syncVm.releaseNetwork();
        stopApkServer();   // ADFA-4785
    }

    private void confirmStop() {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.k2go_clone_stopshare_title))
                .setMessage(getString(R.string.k2go_clone_stopshare_msg))
                .setNegativeButton(getString(R.string.k2go_cancel), null)
                .setPositiveButton(getString(R.string.k2go_clone_stop_confirm), (d, w) -> {
                    if (transport != null) transport.stop();
                    daemonStarted = false;
                    userStopped = true;   // do not auto-restart on the next render
                    render();
                })
                .show();
    }

    private void simpleState(String cap, String sub) {
        qr.setImageBitmap(null);
        caption.setText(cap);
        subCaption.setText(sub);
        setFallback(null);
        advance.setVisibility(View.GONE);
        stop.setVisibility(View.GONE);
        footer.setText("");
    }

    private void showCodeAsText(String payload) {
        currentPayload = payload;
        codetext.setText(payload);
        showcode.setVisibility(View.VISIBLE);
        codeblock.setVisibility(codeExpanded ? View.VISIBLE : View.GONE);
    }

    private void setQr(String data) {
        int px = Math.round(220 * getResources().getDisplayMetrics().density);
        qr.setImageBitmap(QrCodec.encode(data, px));
    }

    private void setFallback(String[] values) {
        fallbackValues.removeAllViews();
        if (values == null || values.length == 0) { fallback.setVisibility(View.GONE); return; }
        fallback.setVisibility(View.VISIBLE);
        for (String val : values) {
            TextView t = new TextView(requireContext());
            t.setText(val);
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
            t.setTextIsSelectable(true);
            fallbackValues.addView(t);
        }
    }

    // ---- step badges (same style as Connect): number kept, corner check when done ----
    private void buildSteps(boolean twoSteps) {   // ADFA-4785: 3-step spine (Connect / Get app / Copy)
        steps.removeAllViews();
        steps.setVisibility(View.VISIBLE);
        int active = sendApp ? 2 : (stage == Stage.JOIN ? 1 : 3);
        steps.addView(badge("1", getString(R.string.k2go_badge_join), active == 1, active > 1));
        steps.addView(arrow());
        steps.addView(badge("2", getString(R.string.k2go_badge_getapp), active == 2, getAppDone));
        steps.addView(arrow());
        steps.addView(badge("3", getString(R.string.k2go_badge_copy), active == 3, false));
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
        advance.setBackgroundResource(filled ? R.drawable.k2go_primary_bg : 0);
        advance.setTextColor(ContextCompat.getColor(requireContext(), filled ? R.color.k2go_on_teal : R.color.k2go_teal));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
