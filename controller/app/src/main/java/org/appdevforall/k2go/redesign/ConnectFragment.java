package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.hotspot.HotspotMode;
import org.appdevforall.k2go.hotspot.HotspotMode.Mode;
import org.appdevforall.k2go.hotspot.HotspotPermissions;
import org.appdevforall.k2go.hotspot.LocalHotspotManager;
import org.appdevforall.k2go.hotspot.domain.HotspotHolders;
import org.appdevforall.k2go.sync.transport.NetworkInterfaces;
import org.appdevforall.k2go.sync.transport.NetworkStateLiveData;
import org.appdevforall.k2go.ui.dialog.BrandDialog;

/**
 * Connect tab (ADFA-4776; redesigned ADFA-5154). Let a nearby device browse the library.
 *
 * <p>On the app's LocalOnly hotspot the flow is two codes — (1) Join, (2) Open — and the redesign
 * shows BOTH at once on one scrollable page (no vanishing wizard step): the user builds a stable
 * spatial map instead of chasing a code that swaps in place. On a shared Wi-Fi it stays one code
 * (Open), shown in the single container. Reuses LocalHotspotManager, NetworkInterfaces and the shared
 * QrSection (ADFA-5157).
 *
 * <p>Both hotspot codes need the hotspot ON (ssid/pass for Join, the IP for Open), and the manager
 * publishes all three together at Phase.ON. So while it is still starting the two sections show a
 * placeholder in the QR slot (record-time on LocalOnly, so this is a blink); a genuine failure or an
 * unsupported device is a single fact, shown once in the single container rather than as two dead
 * frames.
 */
public class ConnectFragment extends Fragment {

    // ADFA-5157: the "section" holder and its setQr/setFallback logic moved to the shared QrSection
    // class so the Clone (Send) redesign reuses it instead of copying it. Three instances here — Join,
    // Open, and the single container used for Wi-Fi / failed / no-system.

    // K2GO-577: the shared Hotspot / Wi-Fi rule (Clone and Share use it too): the choice and Connect's
    // hold. Wi-Fi gives the hotspot back; leaving the screen keeps it, so joined devices keep browsing.
    private HotspotMode hsMode;
    // ADFA-5150: Connect shares this device's library — with no system there is nothing to serve, and
    // the QR would point at a dead port. Read on onResume (a system is not gained while this is open).

    private final LocalHotspotManager hs = LocalHotspotManager.get();
    private ActivityResultLauncher<String[]> hotspotPerm;

    private TextView tabHotspot, tabWifi, finish, connFooter;
    // ADFA-5346: advance CTA is a MaterialButton (shape/size from the shared style); styleAdvance only
    // toggles fill/emphasis.
    private com.google.android.material.button.MaterialButton advance;
    private LinearLayout steps, two, single, hint;
    private QrSection secJoin, secOpen, secSingle;

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        hsMode = HotspotMode.of(requireContext(), HotspotHolders.Holder.CONNECT, Mode.HOTSPOT);
        hotspotPerm = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    hsMode.resume();
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

        secJoin = new QrSection(v, R.id.k2go_conn_qr1_frame, R.id.k2go_conn_qr1, R.id.k2go_conn_qr1_ph,
                R.id.k2go_conn_caption1, R.id.k2go_conn_subcaption1,
                R.id.k2go_conn_fallback_toggle1, R.id.k2go_conn_fallback1, R.id.k2go_conn_fallback_values1);
        secOpen = new QrSection(v, R.id.k2go_conn_qr2_frame, R.id.k2go_conn_qr2, R.id.k2go_conn_qr2_ph,
                R.id.k2go_conn_caption2, R.id.k2go_conn_subcaption2,
                R.id.k2go_conn_fallback_toggle2, R.id.k2go_conn_fallback2, R.id.k2go_conn_fallback_values2);
        secSingle = new QrSection(v, R.id.k2go_conn_qrS_frame, R.id.k2go_conn_qrS, R.id.k2go_conn_qrS_ph,
                R.id.k2go_conn_captionS, R.id.k2go_conn_subcaptionS,
                R.id.k2go_conn_fallback_toggleS, R.id.k2go_conn_fallbackS, R.id.k2go_conn_fallback_valuesS);

        advance = v.findViewById(R.id.k2go_conn_advance);
        finish = v.findViewById(R.id.k2go_conn_finish);
        connFooter = v.findViewById(R.id.k2go_conn_footer);

        tabHotspot.setOnClickListener(x -> requestMode(Mode.HOTSPOT));
        tabWifi.setOnClickListener(x -> requestMode(Mode.WIFI));
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

        setMode(Mode.HOTSPOT);
        return v;
    }

    /** A tab tap. Leaving Hotspot gives it back and drops joined devices, so warn when some may be on. */
    private void requestMode(Mode target) {
        if (!hsMode.switchDropsClients(target)) { setMode(target); return; }
        new BrandDialog(requireContext())
                .setTitle(getString(R.string.k2go_clone_switch_title, getString(R.string.k2go_wifi)))
                .setMessage(R.string.k2go_connect_switch_msg)
                .setNegative(R.string.k2go_cancel, null)
                .setPositive(R.string.k2go_clone_switch_confirm, () -> { if (isAdded()) setMode(target); })
                .show();
    }

    /** A fresh choice: Hotspot holds it (one permission prompt), Wi-Fi gives it back. */
    private void setMode(Mode m) {
        if (hsMode.choose(m)) hotspotPerm.launch(HotspotPermissions.required());
        render();
    }

    private static String browseUrl(String ip) { return "http://" + ip + ":8085"; }

    @Override
    public void onResume() {
        super.onResume();
        // ADFA-5150/5312: redraw on the way to the front — a system may have been recovered, or an
        // install may have finished, while the user was away. render() re-reads the shared verdict.
        // K2GO-439: if the hotspot permission was granted while away (e.g. in Settings), start it now so
        // the user is not stuck on "Starting...". resume() never re-launches the request, which would
        // re-prompt on every resume.
        hsMode.resume();
        render();
    }

    @Override
    public void onDestroyView() {
        // K2GO-375: stop any "resolving…" ellipsis so its Handler cannot tick a destroyed TextView.
        if (secJoin != null) secJoin.stopPending();
        if (secOpen != null) secOpen.stopPending();
        if (secSingle != null) secSingle.stopPending();
        super.onDestroyView();
    }

    private void render() {
        if (!isAdded() || secSingle == null) return;
        SegmentedTabs.paint(tabHotspot, hsMode.isHotspot());
        SegmentedTabs.paint(tabWifi, !hsMode.isHotspot());
        finish.setVisibility(View.GONE);
        connFooter.setVisibility(View.VISIBLE);   // default; the no-system state hides it
        advance.setVisibility(View.GONE);
        // ADFA-5312: branch on the shared verdict. During an install the marker made isSystemInstalled()
        // read false and this screen offered Recover over a system that is present and mid-setup.
        switch (org.appdevforall.k2go.system.data.SystemFactsReader.verdict(requireContext())) {
            case NO_SYSTEM:
            case DAMAGED:
                noSystemState(); return;
            case INSTALLING:
                busyState(); return;
            default:
                break;   // READY / CLONE_* -> the normal share render handles it
        }
        if (hsMode.isHotspot()) renderHotspot(); else renderWifi();
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
        secSingle.setFallback(requireContext(), null);
        advance.setVisibility(View.VISIBLE);
        advance.setText(R.string.k2go_home_recover);
        styleAdvance(true);
        advance.setOnClickListener(x -> SetupLibraryActivity.recover(requireContext()));
        connFooter.setVisibility(View.GONE);   // no "available" note here
    }

    /**
     * ADFA-5312: a system op (install / module / deep-op) is in progress — the system is present but the
     * server is deliberately down, so there is nothing to share yet and Recover would be wrong. Say it is
     * busy and offer no action; render() reruns on resume when the op finishes.
     */
    private void busyState() {
        showSingle();
        secSingle.frame.setVisibility(View.GONE);
        steps.setVisibility(View.GONE);
        secSingle.caption.setText(R.string.k2go_install_busy);
        secSingle.subCaption.setText("");
        secSingle.setFallback(requireContext(), null);
        advance.setVisibility(View.GONE);
        connFooter.setVisibility(View.GONE);
    }

    private void renderHotspot() {
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = (st != null) ? st.phase : LocalHotspotManager.Phase.OFF;

        if (!LocalHotspotManager.isSupported()) {
            singleStatus(getString(R.string.k2go_connect_hotspot_unsupported), getString(R.string.k2go_connect_try_wifi));
            return;
        }
        // K2GO-471: the hotspot came up but its IPv4 never resolved (the second QR cannot be built).
        // This is a distinct terminal from a start failure: say so plainly and offer a retry instead
        // of the enable-location hint, so the user is never stuck on "Finding hotspot address...".
        if (phase == LocalHotspotManager.Phase.FAILED
                && st != null && st.failureReason == LocalHotspotManager.REASON_ADDRESS_UNRESOLVED) {
            singleStatus(getString(R.string.k2go_connect_address_unresolved), "");
            advance.setVisibility(View.VISIBLE);
            advance.setText(R.string.k2go_home_retry);
            styleAdvance(true);
            // K2GO-471 review: hs.stop runs onDone after an async teardown; guard against the fragment
            // being gone by then. A retry is a fresh choice, so it may ask the permission again.
            advance.setOnClickListener(x -> hs.stop(() -> { if (isAdded()) setMode(Mode.HOTSPOT); }));
            connFooter.setVisibility(View.GONE);
            return;
        }
        // FAILED, or the hotspot permission is not granted (denied): show the hint instead of a
        // perpetual "starting" placeholder (K2GO-439).
        if (phase == LocalHotspotManager.Phase.FAILED
                || (phase != LocalHotspotManager.Phase.ON && !HotspotPermissions.granted(requireContext()))) {
            singleStatus(getString(R.string.k2go_connect_hotspot_failed), getString(R.string.k2go_connect_enable_location));
            return;
        }

        // OFF / STARTING / ON all show the two sections; only ON has scannable codes.
        showTwo();
        buildSteps();
        boolean on = (phase == LocalHotspotManager.Phase.ON);

        if (on) {
            // K2GO-375: one source for the hotspot IP — the reservation's owner. Null until the AP
            // interface has its address; the owner re-emits State when it lands, which reruns render().
            String ip = (st != null) ? st.hotspotIp : null;

            secJoin.setWifiJoin(requireContext(), st.ssid, st.passphrase, R.string.k2go_just_scan);

            secOpen.caption.setText(R.string.k2go_connect_readonly);
            secOpen.subCaption.setText("");
            if (ip == null) {
                // K2GO-375: no dead QR at a guessed 192.168.49.1 — many OEMs don't use the …49.x subnet.
                // Hold the slot with an animated "resolving…" until the real AP IP arrives and render reruns.
                secOpen.setQrPending(requireContext(), getString(R.string.k2go_connect_resolving_address));
                secOpen.setFallback(requireContext(), null);
            } else {
                secOpen.setQr(requireContext(), browseUrl(ip), null);
                secOpen.setFallback(requireContext(), new String[]{browseUrl(ip)});
            }

            finish.setVisibility(View.VISIBLE);
        } else {
            // Starting: hold both slots with an animated placeholder, no scannable code yet.
            String starting = getString(R.string.k2go_connect_starting_hotspot);
            for (QrSection sec : new QrSection[]{secJoin, secOpen}) {
                sec.setQrPending(requireContext(), starting);
                sec.caption.setText("");
                sec.subCaption.setText("");
                sec.setFallback(requireContext(), null);
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
        secSingle.setQr(requireContext(), browseUrl(ip), null);
        secSingle.caption.setText(R.string.k2go_connect_scan_open);
        secSingle.subCaption.setText("");   // ADFA-5236: drop the "same Wi-Fi" subcaption
        secSingle.setFallback(requireContext(), new String[]{browseUrl(ip)});
        finish.setVisibility(View.VISIBLE);
    }

    /** A single status message (starting / failed / unsupported / no Wi-Fi) — no dead QR frame. */
    private void singleStatus(String cap, String sub) {
        showSingle();
        steps.setVisibility(View.GONE);
        secSingle.frame.setVisibility(View.GONE);
        secSingle.caption.setText(cap);
        secSingle.subCaption.setText(sub);
        secSingle.setFallback(requireContext(), null);
        advance.setVisibility(View.GONE);
    }

    private void showTwo() { two.setVisibility(View.VISIBLE); single.setVisibility(View.GONE); }

    private void showSingle() {
        single.setVisibility(View.VISIBLE);
        two.setVisibility(View.GONE);
        secSingle.frame.setVisibility(View.VISIBLE);   // default; status / no-system hide it
    }

    // ADFA-5157: setQr / setFallback / applyFallbackOpen moved to QrSection (shared with Clone).

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
        lbl.setTextAppearance(R.style.TextAppearance_K2Go_Caption);
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

    private void styleAdvance(boolean filled) {
        // ADFA-5346: shape/size from the style; only toggle the fill + label emphasis by state.
        K2GoButtons.setFilledEmphasis(advance, filled);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
