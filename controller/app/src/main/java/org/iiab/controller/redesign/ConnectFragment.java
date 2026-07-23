package org.iiab.controller.redesign;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import org.iiab.controller.R;
import org.iiab.controller.hotspot.LocalHotspotManager;
import org.iiab.controller.sync.transport.NetworkInterfaces;
import org.iiab.controller.sync.transport.QrCodec;

/**
 * Connect tab (ADFA-4776): let a nearby device browse the library. Two-code flow per the final
 * design — on a shared Wi-Fi it's one code (Open); on the app's LocalOnly hotspot it's two
 * (Join → Open). Reuses LocalHotspotManager, NetworkInterfaces and QrCodec — no new transport.
 */
public class ConnectFragment extends Fragment {

    private enum Mode { HOTSPOT, WIFI }
    private enum Stage { JOIN, OPEN }

    private Mode mode = Mode.HOTSPOT;
    private Stage stage = Stage.JOIN;
    private boolean openDone = false;

    private final LocalHotspotManager hs = LocalHotspotManager.get();
    private ActivityResultLauncher<String> locationPerm;

    private TextView tabHotspot, tabWifi, caption, subCaption, advance, finish, fallbackTitle;
    private LinearLayout steps, fallback, fallbackValues;
    private ImageView qr;
    // ADFA-4815: the scan fallback (Wi-Fi/pass or URL) is hidden until tapped, so it only
    // shows up when the scan actually failed — same reveal as the Clone step-3 "show code as text".
    private boolean fallbackOpen = false;

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        locationPerm = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) hs.start(requireContext().getApplicationContext());
                    render();
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_k2go_connect, c, false);
        tabHotspot = v.findViewById(R.id.k2go_conn_hotspot);
        tabWifi = v.findViewById(R.id.k2go_conn_wifi);
        steps = v.findViewById(R.id.k2go_conn_steps);
        qr = v.findViewById(R.id.k2go_conn_qr);
        caption = v.findViewById(R.id.k2go_conn_caption);
        subCaption = v.findViewById(R.id.k2go_conn_subcaption);
        fallback = v.findViewById(R.id.k2go_conn_fallback);
        fallbackTitle = v.findViewById(R.id.k2go_conn_fallback_title);
        fallbackValues = v.findViewById(R.id.k2go_conn_fallback_values);
        int fp = Math.round(6 * getResources().getDisplayMetrics().density);
        fallbackTitle.setPadding(0, fp, 0, fp);
        advance = v.findViewById(R.id.k2go_conn_advance);
        finish = v.findViewById(R.id.k2go_conn_finish);

        tabHotspot.setOnClickListener(x -> setMode(Mode.HOTSPOT));
        tabWifi.setOnClickListener(x -> setMode(Mode.WIFI));
        advance.setOnClickListener(x -> {
            if (mode == Mode.HOTSPOT) { stage = (stage == Stage.JOIN) ? Stage.OPEN : Stage.JOIN; openDone = false; fallbackOpen = false; render(); }
        });
        finish.setOnClickListener(x -> {
            openDone = true;      // tick step 2, briefly show it complete, then go Home
            finish.setEnabled(false);
            render();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded()) return;
                View nav = requireActivity().findViewById(R.id.k2go_bottom_nav);
                if (nav instanceof com.google.android.material.bottomnavigation.BottomNavigationView) {
                    ((com.google.android.material.bottomnavigation.BottomNavigationView) nav).setSelectedItemId(R.id.nav_library);
                }
            }, 1500);
        });

        hs.state().observe(getViewLifecycleOwner(), st -> render());

        setMode(Mode.HOTSPOT);
        return v;
    }

    private void setMode(Mode m) {
        mode = m;
        stage = (m == Mode.HOTSPOT) ? Stage.JOIN : Stage.OPEN;
        openDone = false;
        fallbackOpen = false;   // ADFA-4815: each mode/stage starts with the fallback collapsed
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

    private static String browseUrl(String ip) { return "http://" + ip + ":8085"; }

    private void render() {
        if (!isAdded() || caption == null) return;
        paintTab(tabHotspot, mode == Mode.HOTSPOT);
        paintTab(tabWifi, mode == Mode.WIFI);
        if (finish != null) finish.setVisibility(View.GONE);
        if (mode == Mode.HOTSPOT) renderHotspot(); else renderWifi();
    }

    private void renderHotspot() {
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = (st != null) ? st.phase : LocalHotspotManager.Phase.OFF;

        if (!LocalHotspotManager.isSupported()) {
            simpleState(getString(R.string.k2go_connect_hotspot_unsupported), getString(R.string.k2go_connect_try_wifi));
            return;
        }
        if (phase == LocalHotspotManager.Phase.OFF || phase == LocalHotspotManager.Phase.STARTING) {
            simpleState(getString(R.string.k2go_connect_starting_hotspot), "");
            return;
        }
        if (phase == LocalHotspotManager.Phase.FAILED) {
            simpleState(getString(R.string.k2go_connect_hotspot_failed), getString(R.string.k2go_connect_enable_location));
            return;
        }

        String ssid = (st.ssid != null) ? st.ssid : "";
        String pass = (st.passphrase != null) ? st.passphrase : "";
        buildSteps(true);
        advance.setVisibility(View.VISIBLE);
        if (stage == Stage.JOIN) {
            setQr("WIFI:S:" + ssid + ";T:WPA;P:" + pass + ";;");
            caption.setText(R.string.k2go_scan_join_hotspot);
            subCaption.setText(R.string.k2go_just_scan);
            setFallback(new String[]{getString(R.string.k2go_fallback_wifi, ssid), getString(R.string.k2go_fallback_pass, pass)});
            advance.setText(R.string.k2go_connect_shownext);
            styleAdvance(true);
        } else {
            String ip = NetworkInterfaces.discover().hotspotIp;
            if (ip == null) ip = "192.168.49.1";
            setQr(browseUrl(ip));
            caption.setText(getString(R.string.k2go_connect_scan_open));
            subCaption.setText(getString(R.string.k2go_connect_readonly));
            setFallback(new String[]{browseUrl(ip)});
            advance.setText(getString(R.string.k2go_clone_back_step1));
            styleAdvance(false);
            finish.setVisibility(openDone ? View.GONE : View.VISIBLE);
            advance.setVisibility(openDone ? View.GONE : View.VISIBLE);
        }
    }

    private void renderWifi() {
        buildSteps(false);
        advance.setVisibility(View.GONE);
        String ip = NetworkInterfaces.discover().wifiIp;
        if (ip == null) {
            simpleState(getString(R.string.k2go_connect_no_wifi), getString(R.string.k2go_connect_join_wifi));
            return;
        }
        setQr(browseUrl(ip));
        caption.setText(getString(R.string.k2go_connect_scan_open));
        subCaption.setText(getString(R.string.k2go_connect_same_wifi));
        setFallback(new String[]{browseUrl(ip)});
        finish.setVisibility(View.VISIBLE);   // same forward exit back to the library
    }

    /** Clear the QR + fallback and show a status message (starting / failed / unsupported). */
    private void simpleState(String cap, String sub) {
        qr.setImageBitmap(null);
        caption.setText(cap);
        subCaption.setText(sub);
        setFallback(null);
        advance.setVisibility(View.GONE);
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
        // ADFA-4815: reveal-on-tap. Collapsed by default so a working scan stays clutter-free;
        // tapping the title shows the Wi-Fi/pass (or URL) and flips the label to "hide".
        applyFallbackOpen();
        fallbackTitle.setOnClickListener(x -> { fallbackOpen = !fallbackOpen; applyFallbackOpen(); });
    }

    private void applyFallbackOpen() {
        fallbackValues.setVisibility(fallbackOpen ? View.VISIBLE : View.GONE);
        fallbackTitle.setText(fallbackOpen
                ? getString(R.string.k2go_clone_hide_code)
                : getString(R.string.k2go_scan_didnt_work) + "  ▾");
        fallbackTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
    }

    // ---- step badges: numbered circle that KEEPS its number and gains a corner check when done ----
    private void buildSteps(boolean twoSteps) {
        steps.removeAllViews();
        if (!twoSteps) { steps.setVisibility(View.GONE); return; }
        steps.setVisibility(View.VISIBLE);
        boolean atOpen = (stage == Stage.OPEN);
        steps.addView(badge("1", "Join", !atOpen, atOpen));   // active when on Join; done (check) when on Open
        steps.addView(arrow());
        steps.addView(badge("2", "Open", atOpen && !openDone, openDone));
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
        if (filled) {
            g.setColor(ContextCompat.getColor(ctx, R.color.k2go_teal));
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(2), ContextCompat.getColor(ctx, R.color.k2go_muted));
        }
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

        int box = dp(44);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(box, box);
        col.addView(fl, flp);

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
        advance.setTextColor(ContextCompat.getColor(requireContext(),
                filled ? R.color.k2go_on_teal : R.color.k2go_teal));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
