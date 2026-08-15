package org.iiab.controller.redesign;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
import org.iiab.controller.sync.transport.NetworkStateLiveData;
import org.iiab.controller.sync.transport.QrCodec;

/**
 * Connect tab (ADFA-4776; redesigned ADFA-5154). Let a nearby device browse the library.
 *
 * <p>On the app's LocalOnly hotspot the flow is two codes — (1) Join, (2) Open — and the redesign
 * shows BOTH at once on one scrollable page (no vanishing wizard step): the user builds a stable
 * spatial map instead of chasing a code that swaps in place. On a shared Wi-Fi it stays one code
 * (Open), shown in the single container. Reuses LocalHotspotManager, NetworkInterfaces and QrCodec.
 *
 * <p>Both hotspot codes need the hotspot ON (ssid/pass for Join, the IP for Open), and the manager
 * publishes all three together at Phase.ON. So while it is still starting the two sections show a
 * placeholder in the QR slot (record-time on LocalOnly, so this is a blink); a genuine failure or an
 * unsupported device is a single fact, shown once in the single container rather than as two dead
 * frames.
 */
public class ConnectFragment extends Fragment {

    private enum Mode { HOTSPOT, WIFI }

    /**
     * One QR "section": its slot (frame + code + placeholder), caption/subcaption and its own
     * reveal-on-tap scan fallback (ADFA-4815). Three of these — Join, Open, and the single
     * container used for Wi-Fi / failed / no-system — so the three no longer need parallel fields.
     */
    private static final class Section {
        final FrameLayout frame;
        final ImageView qr;
        final TextView ph, caption, subCaption, fallbackToggle;
        final LinearLayout fallback, fallbackValues;
        boolean fbOpen;   // this section's fallback reveal state, kept across re-renders

        Section(View r, int frameId, int qrId, int phId, int capId, int subId,
                int toggleId, int fallbackId, int valuesId) {
            frame = r.findViewById(frameId);
            qr = r.findViewById(qrId);
            ph = r.findViewById(phId);
            caption = r.findViewById(capId);
            subCaption = r.findViewById(subId);
            fallbackToggle = r.findViewById(toggleId);
            fallback = r.findViewById(fallbackId);
            fallbackValues = r.findViewById(valuesId);
        }
    }

    private Mode mode = Mode.HOTSPOT;
    // ADFA-5150: Connect shares this device's library — with no system there is nothing to serve, and
    // the QR would point at a dead port. Read on onResume (a system is not gained while this is open).
    private boolean systemPresent = true;

    private final LocalHotspotManager hs = LocalHotspotManager.get();
    private ActivityResultLauncher<String> locationPerm;

    private TextView tabHotspot, tabWifi, advance, finish, connFooter;
    private LinearLayout steps, two, single, hint;
    private Section secJoin, secOpen, secSingle;

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
        two = v.findViewById(R.id.k2go_conn_two);
        single = v.findViewById(R.id.k2go_conn_single);
        hint = v.findViewById(R.id.k2go_conn_hint);

        secJoin = new Section(v, R.id.k2go_conn_qr1_frame, R.id.k2go_conn_qr1, R.id.k2go_conn_qr1_ph,
                R.id.k2go_conn_caption1, R.id.k2go_conn_subcaption1,
                R.id.k2go_conn_fallback_toggle1, R.id.k2go_conn_fallback1, R.id.k2go_conn_fallback_values1);
        secOpen = new Section(v, R.id.k2go_conn_qr2_frame, R.id.k2go_conn_qr2, R.id.k2go_conn_qr2_ph,
                R.id.k2go_conn_caption2, R.id.k2go_conn_subcaption2,
                R.id.k2go_conn_fallback_toggle2, R.id.k2go_conn_fallback2, R.id.k2go_conn_fallback_values2);
        secSingle = new Section(v, R.id.k2go_conn_qrS_frame, R.id.k2go_conn_qrS, R.id.k2go_conn_qrS_ph,
                R.id.k2go_conn_captionS, R.id.k2go_conn_subcaptionS,
                R.id.k2go_conn_fallback_toggleS, R.id.k2go_conn_fallbackS, R.id.k2go_conn_fallback_valuesS);

        advance = v.findViewById(R.id.k2go_conn_advance);
        finish = v.findViewById(R.id.k2go_conn_finish);
        connFooter = v.findViewById(R.id.k2go_conn_footer);

        tabHotspot.setOnClickListener(x -> setMode(Mode.HOTSPOT));
        tabWifi.setOnClickListener(x -> setMode(Mode.WIFI));
        finish.setOnClickListener(x -> {
            finish.setEnabled(false);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded()) return;
                View nav = requireActivity().findViewById(R.id.k2go_bottom_nav);
                if (nav instanceof com.google.android.material.bottomnavigation.BottomNavigationView) {
                    ((com.google.android.material.bottomnavigation.BottomNavigationView) nav).setSelectedItemId(R.id.nav_library);
                }
            }, 300);
        });

        hs.state().observe(getViewLifecycleOwner(), st -> render());
        // ADFA-5064: redraw when the device's network changes from outside the app (e.g. the user
        // turns Wi-Fi on after landing on a blank QR); render() re-reads the IP via discover().
        NetworkStateLiveData.get(requireContext()).observe(getViewLifecycleOwner(), net -> render());

        // ADFA-5150: seed before the first render so a systemless open never flashes the QR flow;
        // onResume re-reads it.
        systemPresent = org.iiab.controller.SystemStateEvaluator.isSystemInstalled(requireContext());
        setMode(Mode.HOTSPOT);
        return v;
    }

    private void setMode(Mode m) {
        mode = m;
        secJoin.fbOpen = secOpen.fbOpen = secSingle.fbOpen = false;   // each mode starts collapsed
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

    @Override
    public void onResume() {
        super.onResume();
        // ADFA-5150: refresh the system fact on the way to the front and redraw — a system may have
        // been recovered while the user was away in the Recover flow this screen sends them to.
        systemPresent = org.iiab.controller.SystemStateEvaluator.isSystemInstalled(requireContext());
        render();
    }

    private void render() {
        if (!isAdded() || secSingle == null) return;
        paintTab(tabHotspot, mode == Mode.HOTSPOT);
        paintTab(tabWifi, mode == Mode.WIFI);
        finish.setVisibility(View.GONE);
        connFooter.setVisibility(View.VISIBLE);   // default; the no-system state hides it
        advance.setVisibility(View.GONE);
        if (!systemPresent) { noSystemState(); return; }
        if (mode == Mode.HOTSPOT) renderHotspot(); else renderWifi();
    }

    /**
     * ADFA-5150: no system, so nothing to share. Instead of a QR pointing at a dead server, say so and
     * offer the one move that helps — Recover. Reuses {@code advance} as the action.
     */
    private void noSystemState() {
        showSingle();
        secSingle.frame.setVisibility(View.GONE);
        steps.setVisibility(View.GONE);
        secSingle.caption.setText(R.string.k2go_connect_no_system);
        secSingle.subCaption.setText("");
        setFallback(secSingle, null);
        advance.setVisibility(View.VISIBLE);
        advance.setText(R.string.k2go_home_recover);
        styleAdvance(true);
        advance.setOnClickListener(x -> SetupLibraryActivity.recover(requireContext()));
        connFooter.setVisibility(View.GONE);   // no "available" note here
    }

    private void renderHotspot() {
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = (st != null) ? st.phase : LocalHotspotManager.Phase.OFF;

        if (!LocalHotspotManager.isSupported()) {
            singleStatus(getString(R.string.k2go_connect_hotspot_unsupported), getString(R.string.k2go_connect_try_wifi));
            return;
        }
        if (phase == LocalHotspotManager.Phase.FAILED) {
            singleStatus(getString(R.string.k2go_connect_hotspot_failed), getString(R.string.k2go_connect_enable_location));
            return;
        }

        // OFF / STARTING / ON all show the two sections; only ON has scannable codes.
        showTwo();
        buildSteps();
        boolean on = (phase == LocalHotspotManager.Phase.ON);

        if (on) {
            String ssid = (st != null && st.ssid != null) ? st.ssid : "";
            String pass = (st != null && st.passphrase != null) ? st.passphrase : "";
            String ip = NetworkInterfaces.discover().hotspotIp;
            if (ip == null) ip = "192.168.49.1";

            setQr(secJoin, "WIFI:S:" + ssid + ";T:WPA;P:" + pass + ";;", null);
            secJoin.caption.setText(R.string.k2go_just_scan);
            secJoin.subCaption.setText("");
            setFallback(secJoin, new String[]{
                    getString(R.string.k2go_fallback_wifi, ssid), getString(R.string.k2go_fallback_pass, pass)});

            setQr(secOpen, browseUrl(ip), null);
            secOpen.caption.setText(R.string.k2go_connect_readonly);
            secOpen.subCaption.setText("");
            setFallback(secOpen, new String[]{browseUrl(ip)});

            finish.setVisibility(View.VISIBLE);
        } else {
            // Starting: hold both slots with a placeholder caption, no scannable code yet.
            String starting = getString(R.string.k2go_connect_starting_hotspot);
            for (Section sec : new Section[]{secJoin, secOpen}) {
                setQr(sec, null, starting);
                sec.caption.setText("");
                sec.subCaption.setText("");
                setFallback(sec, null);
            }
            finish.setVisibility(View.VISIBLE);
        }
    }

    private void renderWifi() {
        showSingle();
        steps.setVisibility(View.GONE);
        String ip = NetworkInterfaces.discover().wifiIp;
        if (ip == null) {
            singleStatus(getString(R.string.k2go_connect_no_wifi), getString(R.string.k2go_connect_join_wifi));
            return;
        }
        secSingle.frame.setVisibility(View.VISIBLE);
        setQr(secSingle, browseUrl(ip), null);
        secSingle.caption.setText(R.string.k2go_connect_scan_open);
        secSingle.subCaption.setText(R.string.k2go_connect_same_wifi);
        setFallback(secSingle, new String[]{browseUrl(ip)});
        finish.setVisibility(View.VISIBLE);
    }

    /** A single status message (starting / failed / unsupported / no Wi-Fi) — no dead QR frame. */
    private void singleStatus(String cap, String sub) {
        showSingle();
        steps.setVisibility(View.GONE);
        secSingle.frame.setVisibility(View.GONE);
        secSingle.caption.setText(cap);
        secSingle.subCaption.setText(sub);
        setFallback(secSingle, null);
        advance.setVisibility(View.GONE);
    }

    private void showTwo() { two.setVisibility(View.VISIBLE); single.setVisibility(View.GONE); }

    private void showSingle() {
        single.setVisibility(View.VISIBLE);
        two.setVisibility(View.GONE);
        secSingle.frame.setVisibility(View.VISIBLE);   // default; status / no-system hide it
    }

    /** Draw {@code data} into the section's QR, or clear it and show {@code placeholder} in the slot. */
    private void setQr(Section sec, String data, String placeholder) {
        if (data == null) {
            sec.qr.setImageBitmap(null);
            sec.ph.setText(placeholder == null ? "" : placeholder);
            sec.ph.setVisibility(View.VISIBLE);
            return;
        }
        int px = Math.round(200 * getResources().getDisplayMetrics().density);
        sec.qr.setImageBitmap(QrCodec.encode(data, px));
        sec.ph.setVisibility(View.GONE);
    }

    private void setFallback(Section sec, String[] vals) {
        sec.fallbackValues.removeAllViews();
        if (vals == null || vals.length == 0) {
            sec.fallback.setVisibility(View.GONE);
            sec.fallbackToggle.setVisibility(View.GONE);
            return;
        }
        for (String val : vals) {
            TextView t = new TextView(requireContext());
            t.setText(val);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
            t.setTextIsSelectable(true);
            sec.fallbackValues.addView(t);
        }
        // ADFA-4815: reveal-on-tap. The toggle sits under the caption; the quote block with the values
        // stays hidden until tapped, so a working scan stays clutter-free.
        sec.fallbackToggle.setVisibility(View.VISIBLE);
        applyFallbackOpen(sec);
        sec.fallbackToggle.setOnClickListener(x -> { sec.fbOpen = !sec.fbOpen; applyFallbackOpen(sec); });
    }

    private void applyFallbackOpen(Section sec) {
        sec.fallback.setVisibility(sec.fbOpen ? View.VISIBLE : View.GONE);
        sec.fallbackToggle.setText(sec.fbOpen
                ? getString(R.string.k2go_hide) + "  ▴"
                : getString(R.string.k2go_scan_didnt_work) + "  ▸");
    }

    // ---- 1·2 stepper for orientation. Both are present at once, so both read as active. ----
    private void buildSteps() {
        steps.removeAllViews();
        steps.setVisibility(View.VISIBLE);
        steps.addView(badge("1", getString(R.string.k2go_connect_step_join)));
        steps.addView(arrow());
        steps.addView(badge("2", getString(R.string.k2go_connect_step_open)));
    }

    private View badge(String num, String label) {
        Context ctx = requireContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout fl = new FrameLayout(ctx);
        int d = dp(38);
        View circle = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ContextCompat.getColor(ctx, R.color.k2go_teal));
        circle.setBackground(g);
        fl.addView(circle, new FrameLayout.LayoutParams(d, d));

        TextView t = new TextView(ctx);
        t.setText(num);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_on_teal));
        fl.addView(t, new FrameLayout.LayoutParams(d, d));

        int box = dp(44);
        col.addView(fl, new LinearLayout.LayoutParams(box, box));

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
