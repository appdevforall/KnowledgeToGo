package org.iiab.controller.redesign;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import org.iiab.controller.R;
import org.iiab.controller.SyncHandshakeHelper;
import org.iiab.controller.hotspot.LocalHotspotManager;
import org.iiab.controller.sync.domain.ShareConfig;
import org.iiab.controller.sync.presentation.SyncStateViewModel;
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
    private boolean userStopped = false;  // true after Stop, prevents auto-restart on the next render

    private ActivityResultLauncher<String> locationPerm;

    private TextView tabSend, tabReceive, tabHotspot, tabWifi, caption, subCaption, advance, stop, footer, receiveNote;
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
        receiveNote = v.findViewById(R.id.k2go_clone_receive_note);
        showcode = v.findViewById(R.id.k2go_clone_showcode);
        codeblock = v.findViewById(R.id.k2go_clone_codeblock);
        codetext = v.findViewById(R.id.k2go_clone_codetext);
        copyBtn = v.findViewById(R.id.k2go_clone_copy);
        shareBtn = v.findViewById(R.id.k2go_clone_share);

        SyncStateViewModel syncVm = new ViewModelProvider(requireActivity()).get(SyncStateViewModel.class);
        transport = syncVm.getTransport();
        shareConfig = ShareConfig.defaults();

        tabSend.setOnClickListener(x -> setSide(Side.SEND));
        tabReceive.setOnClickListener(x -> setSide(Side.RECEIVE));
        tabHotspot.setOnClickListener(x -> setMode(Mode.HOTSPOT));
        tabWifi.setOnClickListener(x -> setMode(Mode.WIFI));
        advance.setOnClickListener(x -> {
            if (mode == Mode.HOTSPOT) {
                stage = (stage == Stage.JOIN) ? Stage.START : Stage.JOIN;
                render();
            }
        });
        showcode.setOnClickListener(x -> {
            codeExpanded = !codeExpanded;
            codeblock.setVisibility(codeExpanded ? View.VISIBLE : View.GONE);
            showcode.setText(codeExpanded ? "Show code as text  ▴" : "Show code as text  ▾");
        });
        copyBtn.setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("K2Go transfer code", currentPayload));
            Toast.makeText(requireContext(), "Code copied", Toast.LENGTH_SHORT).show();
        });
        shareBtn.setOnClickListener(x -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentPayload);
            startActivity(Intent.createChooser(i, "Send transfer code"));
        });

        hs.state().observe(getViewLifecycleOwner(), st -> render());

        setSide(Side.SEND);
        return v;
    }

    private void setSide(Side sd) {
        side = sd;
        if (sd == Side.SEND) setMode(Mode.HOTSPOT); else render();
    }

    private void setMode(Mode m) {
        mode = m;
        stage = (m == Mode.HOTSPOT) ? Stage.JOIN : Stage.START;
        if (m == Mode.HOTSPOT) ensureHotspot();
        render();
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
        daemonStarting = true;
        tempPass = SyncHandshakeHelper.generateSecurePassword();
        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        hostHasRootfs = rootfsDir.exists() && rootfsDir.isDirectory();
        if (!rootfsDir.exists()) rootfsDir.mkdirs();
        final String shareDir = rootfsDir.getAbsolutePath();
        final Context app = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity act = requireActivity();  // capture before the thread
        new Thread(() -> {
            final boolean ok = transport.startServer(app, shareConfig, tempPass, shareDir);
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                daemonStarting = false; daemonStarted = ok; render();
            });
        }, "clone-share-daemon").start();
    }

    private static int archBits() {
        return (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0) ? 64 : 32;
    }

    private void render() {
        if (!isAdded() || caption == null) return;
        if (showcode != null) { showcode.setVisibility(View.GONE); codeblock.setVisibility(View.GONE); }
        paintTab(tabSend, side == Side.SEND);
        paintTab(tabReceive, side == Side.RECEIVE);

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
            receiveNote.setVisibility(View.VISIBLE);
            return;
        }
        receiveNote.setVisibility(View.GONE);
        netRow.setVisibility(View.VISIBLE);
        qr.setVisibility(View.VISIBLE);
        caption.setVisibility(View.VISIBLE);
        subCaption.setVisibility(View.VISIBLE);
        footer.setVisibility(View.VISIBLE);   // RECEIVE hid it; restore for SEND
        stop.setVisibility(View.GONE);
        paintTab(tabHotspot, mode == Mode.HOTSPOT);
        paintTab(tabWifi, mode == Mode.WIFI);

        if (mode == Mode.HOTSPOT) renderHotspot(); else renderWifi();
    }

    private void renderHotspot() {
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = (st != null) ? st.phase : LocalHotspotManager.Phase.OFF;
        if (!LocalHotspotManager.isSupported()) { simpleState("Hotspot needs Android 8 or newer", "Try the Wi-Fi option instead."); return; }
        if (phase == LocalHotspotManager.Phase.OFF || phase == LocalHotspotManager.Phase.STARTING) { simpleState("Starting hotspot…", ""); return; }
        if (phase == LocalHotspotManager.Phase.FAILED) { simpleState("Couldn't start the hotspot", "Enable Location, then tap Hotspot again."); return; }

        String ssid = (st.ssid != null) ? st.ssid : "";
        String pass = (st.passphrase != null) ? st.passphrase : "";
        String ip = NetworkInterfaces.discover().hotspotIp;
        if (ip == null) ip = "192.168.49.1";

        buildSteps(true);
        advance.setVisibility(View.VISIBLE);
        if (stage == Stage.JOIN) {
            setQr("WIFI:S:" + ssid + ";T:WPA;P:" + pass + ";;");
            caption.setText("Scan code 1 to join");
            subCaption.setText(R.string.k2go_just_scan);
            setFallback(new String[]{"Wi-Fi: " + ssid, "Password: " + pass});
            advance.setText("Show code 2 ›");
            styleAdvance(true);
            footer.setText("");
        } else {
            advance.setText("‹ Back to step 1");
            styleAdvance(false);
            ensureDaemon(ip);
            renderStartState(ip, true);
        }
    }

    private void renderWifi() {
        String ip = NetworkInterfaces.discover().wifiIp;
        if (ip == null) { buildSteps(false); advance.setVisibility(View.GONE); simpleState("Not on a Wi-Fi network", "Join a Wi-Fi, or use Hotspot."); return; }
        buildSteps(false);
        advance.setVisibility(View.GONE);
        ensureDaemon(ip);
        renderStartState(ip, false);
    }

    /** Step-2 state: starting -> stopped (Start sharing) -> running (QR + Stop sharing). */
    private void renderStartState(String ip, boolean twoCode) {
        if (daemonStarting) {
            qr.setImageBitmap(null);
            caption.setText("Starting the service…");
            subCaption.setText("");
            setFallback(null); footer.setText(""); stop.setVisibility(View.GONE);
            return;
        }
        if (!daemonStarted) {   // stopped by the user (or failed to start)
            qr.setImageBitmap(null);
            caption.setText("Sharing stopped");
            subCaption.setText("Start the service to share this phone's library.");
            setFallback(null); footer.setText("");
            showStartButton();
            return;
        }
        String payload = SyncHandshakeHelper.createPayload(ip, shareConfig.rsyncPort, shareConfig.user, tempPass, hostHasRootfs, archBits());
        qr.setImageBitmap(SyncHandshakeHelper.generateQrCode(payload, 500));
        caption.setText(twoCode ? "Ready! Scan code 2 to start the transfer" : "Ready! Scan to start the transfer");
        subCaption.setText("The copy begins when they scan this one.");
        setFallback(null);
        showCodeAsText(payload);
        showStopButton();
        footer.setText("Stays on until you Stop.");
    }

    private void showStopButton() {
        stop.setVisibility(View.VISIBLE);
        stop.setText("Stop sharing");
        stop.setBackgroundResource(R.drawable.k2go_turnoff_bg);
        stop.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_clay));
        stop.setOnClickListener(x -> confirmStop());
    }

    private void showStartButton() {
        stop.setVisibility(View.VISIBLE);
        stop.setText("Start sharing");
        stop.setBackgroundResource(R.drawable.k2go_primary_bg);
        stop.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_on_teal));
        stop.setOnClickListener(x -> { userStopped = false; render(); });
    }

    private void confirmStop() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Stop sharing?")
                .setMessage("This stops the copy. The other device can start again by re-scanning.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Stop", (d, w) -> {
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
    private void buildSteps(boolean twoSteps) {
        steps.removeAllViews();
        if (!twoSteps) { steps.setVisibility(View.GONE); return; }
        steps.setVisibility(View.VISIBLE);
        boolean atStart = (stage == Stage.START);
        steps.addView(badge("1", "Join", !atStart, atStart));
        steps.addView(arrow());
        steps.addView(badge("2", "Start", atStart && !daemonStarted, atStart && daemonStarted));
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
