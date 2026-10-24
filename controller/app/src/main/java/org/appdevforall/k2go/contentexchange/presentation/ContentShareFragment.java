/*
 * ============================================================================
 * Name        : ContentShareFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. Shows the Share invite: two stacked QR sections (join the hotspot, then
 *               scan to receive) once the sender foreground service has opened the share session and
 *               the hotspot is up. Reads state only (the ShareSession + the shared LocalHotspotManager
 *               state); all work is the service's. Done closes the gate + the hotspot via the service.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.ShareHandshake;
import org.appdevforall.k2go.contentexchange.domain.ShareTransport;
import org.appdevforall.k2go.hotspot.HotspotPermissions;
import org.appdevforall.k2go.hotspot.LocalHotspotManager;
import org.appdevforall.k2go.redesign.QrSection;

public class ContentShareFragment extends Fragment {

    private TextView subtitle;
    private TextView status;
    private TextView waiting;
    private View joinRoot;
    private View receiveRoot;
    private QrSection secJoin;
    private QrSection secReceive;
    private Button done;

    private final ShareSession session = ContentShareService.session();
    private final LocalHotspotManager hs = LocalHotspotManager.get();

    /** One-shot guard so a re-entrant render() does not schedule the back-pop twice (K2GO-427). */
    private boolean leaving;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_share, container, false);
        subtitle = root.findViewById(R.id.k2go_cxs_subtitle);
        status = root.findViewById(R.id.k2go_cxs_status);
        waiting = root.findViewById(R.id.k2go_cxs_waiting);
        done = root.findViewById(R.id.k2go_cxs_done);

        joinRoot = root.findViewById(R.id.k2go_cxs_join);
        receiveRoot = root.findViewById(R.id.k2go_cxs_receive);
        secJoin = new QrSection(joinRoot, R.id.sec_qr_frame, R.id.sec_qr, R.id.sec_qr_ph,
                R.id.sec_caption, R.id.sec_subcaption, R.id.sec_fallback_toggle, R.id.sec_fallback, R.id.sec_fallback_values);
        secReceive = new QrSection(receiveRoot, R.id.sec_qr_frame, R.id.sec_qr, R.id.sec_qr_ph,
                R.id.sec_caption, R.id.sec_subcaption, R.id.sec_fallback_toggle, R.id.sec_fallback, R.id.sec_fallback_values);
        ((TextView) joinRoot.findViewById(R.id.sec_badge)).setText("1");
        ((TextView) joinRoot.findViewById(R.id.sec_title)).setText(R.string.k2go_cx_share_step_join);
        ((TextView) receiveRoot.findViewById(R.id.sec_badge)).setText("2");
        ((TextView) receiveRoot.findViewById(R.id.sec_title)).setText(R.string.k2go_cx_share_step_receive);

        done.setOnClickListener(v -> {
            ContentShareService.cancel(requireContext());
            goBack();
        });
        // The AP IPv4 lands a beat after the hotspot reports ON; re-render when it (or the phase) changes
        // so the receive QR appears the moment every piece is ready. Observed once, tied to the view.
        hs.state().observe(getViewLifecycleOwner(), st -> render());
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // The token/manifest arrive from the service worker; re-render when the session state changes.
        session.setListener(() -> {
            if (isAdded()) {
                render();
            }
        });
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        session.setListener(null);
    }

    @Override
    public void onDestroyView() {
        // Stop any "resolving..." ellipsis so its Handler cannot tick a destroyed view (K2GO-375).
        if (secJoin != null) {
            secJoin.stopPending();
        }
        if (secReceive != null) {
            secReceive.stopPending();
        }
        leaving = false;
        super.onDestroyView();
    }

    private void render() {
        if (!isAdded() || secReceive == null) {
            return;
        }
        // A cleared / absent session means the share was stopped: leave this screen.
        if (!session.hasSession()) {
            goBack();
            return;
        }
        subtitle.setText(getString(R.string.k2go_cx_share_subtitle_fmt, session.itemCount()));

        if (session.hasError()) {
            showStatus(session.errorMessage() != null
                    ? session.errorMessage() : getString(R.string.k2go_cx_share_error));
            return;
        }
        if (session.isPreparing()) {
            showStatus(getString(R.string.k2go_cx_share_preparing));
            return;
        }
        // READY: the gate exists; the QR needs the hotspot up and its address resolved.
        renderInvite();
    }

    private void renderInvite() {
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = st != null ? st.phase : LocalHotspotManager.Phase.OFF;

        if (!LocalHotspotManager.isSupported()) {
            showStatus(getString(R.string.k2go_cx_share_hotspot_unsupported));
            return;
        }
        if (phase == LocalHotspotManager.Phase.FAILED
                || (phase != LocalHotspotManager.Phase.ON && !HotspotPermissions.granted(requireContext()))) {
            showStatus(getString(R.string.k2go_cx_share_hotspot_failed));
            return;
        }

        // OFF / STARTING / ON: show the two sections; only ON has scannable codes.
        showSections();
        boolean on = phase == LocalHotspotManager.Phase.ON;
        if (!on) {
            String starting = getString(R.string.k2go_cx_share_starting_hotspot);
            for (QrSection sec : new QrSection[]{secJoin, secReceive}) {
                sec.setQrPending(requireContext(), starting);
                sec.caption.setText("");
                sec.subCaption.setText("");
                sec.setFallback(requireContext(), null);
            }
            waiting.setVisibility(View.GONE);
            return;
        }

        String ssid = st != null && st.ssid != null ? st.ssid : "";
        String pass = st != null && st.passphrase != null ? st.passphrase : "";
        String ip = st != null ? st.hotspotIp : null;

        // Section 1: join the hotspot (standard Wi-Fi auto-join QR + the credentials as fallback).
        secJoin.setQr(requireContext(), "WIFI:S:" + ssid + ";T:WPA;P:" + pass + ";;", null);
        secJoin.caption.setText(R.string.k2go_cx_share_join_caption);
        secJoin.subCaption.setText("");
        secJoin.setFallback(requireContext(), new String[]{
                getString(R.string.k2go_fallback_wifi, ssid), getString(R.string.k2go_fallback_pass, pass)});

        // Section 2: the share invite QR. Needs the AP IPv4, which resolves a beat after ON.
        secReceive.caption.setText(R.string.k2go_cx_share_receive_caption);
        secReceive.subCaption.setText("");
        String invite = ip != null
                ? ShareHandshake.encode(ip, ShareTransport.SHARE_PORT, session.token(), session.manifestUrl())
                : null;
        if (invite == null) {
            // No dead QR at a guessed address; hold the slot until the real AP IP arrives (render reruns).
            secReceive.setQrPending(requireContext(), getString(R.string.k2go_cx_share_resolving_address));
            secReceive.setFallback(requireContext(), null);
            waiting.setVisibility(View.GONE);
        } else {
            secReceive.setQr(requireContext(), invite, null);
            secReceive.setFallback(requireContext(), null);
            waiting.setVisibility(View.VISIBLE);
        }
    }

    /** Show a single status line (preparing / hotspot / error) instead of the two QR cards. */
    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        secJoin.stopPending();
        secReceive.stopPending();
        joinRoot.setVisibility(View.GONE);
        receiveRoot.setVisibility(View.GONE);
        waiting.setVisibility(View.GONE);
    }

    private void showSections() {
        status.setVisibility(View.GONE);
        joinRoot.setVisibility(View.VISIBLE);
        receiveRoot.setVisibility(View.VISIBLE);
    }

    /**
     * Leave this screen. Mirrors ExportProgressFragment: the pop is deferred to the next main-loop tick
     * and re-checked there, so a render() driven from inside a FragmentManager transaction (the listener,
     * or the service beginning the session on a later message) never calls popBackStack mid-transaction
     * ("FragmentManager is already executing transactions", K2GO-427).
     */
    private void goBack() {
        if (leaving) {
            return;
        }
        View v = getView();
        if (v == null) {
            return;
        }
        leaving = true;
        v.post(() -> {
            if (!isAdded()) {
                return;
            }
            if (session.hasSession()) {
                leaving = false;   // a run exists now: stay, and let a later leave (Done) reschedule
                return;
            }
            getParentFragmentManager().popBackStack();
        });
    }
}
