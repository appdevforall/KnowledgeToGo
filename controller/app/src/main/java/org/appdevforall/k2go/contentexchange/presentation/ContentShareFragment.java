/*
 * ============================================================================
 * Name        : ContentShareFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. Shows the Share invite: two stacked QR sections (join the hotspot, then
 *               scan to receive) once the sender foreground service has opened the share session.
 *               The Hotspot / Wi-Fi strip is the one place that takes or gives back the share's hold
 *               on the hotspot (HotspotMode, K2GO-577). Done or Back closes the gate and the hold
 *               via the service.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.ShareHandshake;
import org.appdevforall.k2go.contentexchange.domain.ShareTransport;
import org.appdevforall.k2go.hotspot.HotspotMode;
import org.appdevforall.k2go.hotspot.HotspotPermissions;
import org.appdevforall.k2go.hotspot.LocalHotspotManager;
import org.appdevforall.k2go.hotspot.data.ApAddressResolver;
import org.appdevforall.k2go.redesign.CodeText;
import org.appdevforall.k2go.redesign.QrSection;
import org.appdevforall.k2go.redesign.SegmentedTabs;
import org.appdevforall.k2go.sync.transport.NetworkStateLiveData;
import org.appdevforall.k2go.ui.dialog.BrandDialog;
import org.appdevforall.k2go.util.ByteFormatter;

public class ContentShareFragment extends Fragment {

    private TextView subtitle;
    private TextView status;
    private TextView waiting;
    private View joinRoot;
    private View receiveRoot;
    private QrSection secJoin;
    private QrSection secReceive;
    private View codeLabel;
    private View codeBlock;
    private TextView codeText;
    /** The invite shown as text; Copy / Share hand out this exact payload. */
    private String invitePayload = "";
    private View transportRow;
    private TextView transportHotspot;
    private TextView transportWifi;
    private Button done;

    private final ShareSession session = ContentShareService.session();
    private final LocalHotspotManager hs = LocalHotspotManager.get();

    /** One-shot guard so a re-entrant render() does not schedule the back-pop twice (K2GO-427). */
    private boolean leaving;

    private ActivityResultLauncher<String[]> hotspotPerm;

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        // The answer re-applies the choice; it never asks again (HotspotMode keeps that latch).
        hotspotPerm = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
            if (isAdded() && session.hasSession()) {
                mode().resume();
                render();
            }
        });
    }

    /** This run's Hotspot / Wi-Fi choice; it lives in the session so it outlasts this view. */
    private HotspotMode mode() {
        return session.hotspotMode(requireContext());
    }

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
        QrSection.setStep(joinRoot, "1", getString(R.string.k2go_cx_share_step_join));

        codeLabel = root.findViewById(R.id.k2go_code_label);
        codeBlock = root.findViewById(R.id.k2go_code_block);
        codeText = root.findViewById(R.id.k2go_code_text);
        root.findViewById(R.id.k2go_code_copy).setOnClickListener(v -> CodeText.copy(v, invitePayload));
        root.findViewById(R.id.k2go_code_share).setOnClickListener(v -> CodeText.share(requireContext(), invitePayload));

        transportRow = root.findViewById(R.id.k2go_cxs_transport);
        transportHotspot = root.findViewById(R.id.k2go_cxs_transport_hotspot);
        transportWifi = root.findViewById(R.id.k2go_cxs_transport_wifi);
        transportHotspot.setOnClickListener(v -> requestMode(HotspotMode.Mode.HOTSPOT));
        transportWifi.setOnClickListener(v -> requestMode(HotspotMode.Mode.WIFI));

        done.setOnClickListener(v -> stopAndLeave());
        // Back ends the share like Done (a share left running would make the next Share reopen it
        // instead of the new selection), but asks first, as Clone does: Back is easy to hit mid-transfer.
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (session.hasError()) {
                            stopAndLeave();   // nothing is being sent
                            return;
                        }
                        new BrandDialog(requireContext())
                                .setTitle(R.string.k2go_clone_stopshare_title)
                                // Own message, not Clone's "can start again": resume is not
                                // guaranteed for every app yet (K2GO-577 covers ZIM only).
                                .setMessage(R.string.k2go_cx_share_stop_msg)
                                .setNegative(R.string.k2go_cancel, null)
                                .setDestructive(R.string.k2go_clone_stop_confirm, () -> stopAndLeave())
                                .show();
                    }
                });
        // The AP IPv4 lands a beat after the hotspot reports ON; re-render when it (or the phase) changes
        // so the receive QR appears the moment every piece is ready. Observed once, tied to the view.
        hs.state().observe(getViewLifecycleOwner(), st -> render());
        // K2GO-376: also re-render on any network change (Wi-Fi off/on gives a new DHCP IP), so the
        // Wi-Fi-transport QR never advertises a stale station address. Same reactive trigger Connect and
        // Clone use (ADFA-5064); renderInvite() re-resolves the station IP and redraws the QR.
        NetworkStateLiveData.get(requireContext()).observe(getViewLifecycleOwner(), t -> render());
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
        // Apply the choice on entry and on every return: Hotspot holds the hotspot (asks the
        // permission once), Wi-Fi keeps it released.
        if (session.hasSession() && mode().ensure()) {
            hotspotPerm.launch(HotspotPermissions.required());
        }
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
        // Same "N selected : size" line Content manager showed before Share.
        subtitle.setText(getString(R.string.k2go_cx_selected_fmt, session.itemCount(),
                ByteFormatter.humanBytes(session.totalBytes())));

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
        boolean hotspot = mode().isHotspot();
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = st != null ? st.phase : LocalHotspotManager.Phase.OFF;
        String ip;
        if (hotspot) {
            if (!LocalHotspotManager.isSupported()) {
                showModeStatus(R.string.k2go_cx_share_hotspot_unsupported, R.string.k2go_connect_try_wifi);
                return;
            }
            if (phase == LocalHotspotManager.Phase.FAILED) {
                showModeStatus(R.string.k2go_cx_share_hotspot_failed, R.string.k2go_connect_try_wifi);
                return;
            }
            if (phase != LocalHotspotManager.Phase.ON && !HotspotPermissions.granted(requireContext())) {
                // Denied: a tap on Hotspot asks once more (HotspotMode.choose).
                showModeStatus(R.string.k2go_cx_share_hotspot_failed, R.string.k2go_connect_enable_location);
                return;
            }
            if (phase != LocalHotspotManager.Phase.ON) {
                // OFF / STARTING: hold both slots until the hotspot is up.
                showSections(true);
                String starting = getString(R.string.k2go_cx_share_starting_hotspot);
                for (QrSection sec : new QrSection[]{secJoin, secReceive}) {
                    sec.setQrPending(requireContext(), starting);
                    sec.caption.setText("");
                    sec.subCaption.setText("");
                    sec.setFallback(requireContext(), null);
                }
                waiting.setVisibility(View.GONE);
                showInviteText(null);
                return;
            }
            ip = st.hotspotIp;
        } else {
            // Wi-Fi: both phones on the same network, so there is nothing to join and no hotspot.
            ip = ApAddressResolver.resolveStationWifiIp(requireContext());
            if (ip == null) {
                showModeStatus(R.string.k2go_connect_no_wifi, 0);
                return;
            }
        }
        showSections(hotspot);
        if (hotspot) {
            secJoin.setWifiJoin(requireContext(), st.ssid, st.passphrase, R.string.k2go_cx_share_join_caption);
        }

        // Section 2: the share invite QR. Needs the AP IPv4, which resolves a beat after ON.
        secReceive.subCaption.setText("");
        // The core returns its own path (/v1/share/<id>/manifest); the receiver reaches it through
        // the off-box gate prefix, so the invite carries the prefixed path.
        String invite = ip != null
                ? ShareHandshake.encode(ip, ShareTransport.SHARE_PORT, session.token(),
                        ShareTransport.SHARE_PATH_PREFIX + session.manifestUrl())
                : null;
        if (invite == null) {
            // No dead QR at a guessed address; hold the slot until the real AP IP arrives (render reruns).
            secReceive.setQrPending(requireContext(), getString(R.string.k2go_cx_share_resolving_address));
            secReceive.caption.setText("");   // no "Ready! Scan" until there is a QR to scan
            secReceive.setFallback(requireContext(), null);
            waiting.setVisibility(View.GONE);
        } else {
            secReceive.setQr(requireContext(), invite, null);
            secReceive.setFallback(requireContext(), null);
            waiting.setVisibility(View.VISIBLE);
        }
        showInviteText(invite);
    }

    /** Show the invite as copyable text under the QR, or hide the block when there is no invite. */
    private void showInviteText(@Nullable String invite) {
        invitePayload = invite != null ? invite : "";
        codeText.setText(invitePayload);
        int vis = invite != null ? View.VISIBLE : View.GONE;
        codeLabel.setVisibility(vis);
        codeBlock.setVisibility(vis);
    }

    /**
     * A tab tap. Leaving Hotspot gives the hotspot back, which drops a receiver joined to it, so warn
     * first when someone may be connected. A tap on the current tab re-applies it (the retry path
     * after a denied permission).
     */
    private void requestMode(HotspotMode.Mode target) {
        LocalHotspotManager.State st = hs.state().getValue();
        boolean dropsClients = mode().isHotspot() && target == HotspotMode.Mode.WIFI
                && st != null && st.mayHaveClients();
        if (!dropsClients) {
            setMode(target);
            return;
        }
        new BrandDialog(requireContext())
                .setTitle(getString(R.string.k2go_clone_switch_title, getString(R.string.k2go_cx_share_transport_wifi)))
                .setMessage(R.string.k2go_clone_switch_msg)
                .setNegative(R.string.k2go_cancel, null)
                .setPositive(R.string.k2go_clone_switch_confirm, () -> setMode(target))
                .show();
    }

    private void setMode(HotspotMode.Mode target) {
        if (!isAdded() || !session.hasSession()) {
            return;
        }
        if (mode().choose(target)) {
            hotspotPerm.launch(HotspotPermissions.required());
        }
        render();
    }

    /** Show a single status line (preparing / hotspot / error) instead of the two QR cards. */
    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        secJoin.stopPending();
        secReceive.stopPending();
        joinRoot.setVisibility(View.GONE);
        receiveRoot.setVisibility(View.GONE);
        transportRow.setVisibility(View.GONE);
        waiting.setVisibility(View.GONE);
        showInviteText(null);
    }

    /** A status under the strip: the chosen transport cannot carry the share yet. [hint] 0 = none. */
    private void showModeStatus(int message, int hint) {
        showStatus(hint != 0 ? getString(message) + "\n" + getString(hint) : getString(message));
        paintTabs();
    }

    private void paintTabs() {
        transportRow.setVisibility(View.VISIBLE);
        boolean hotspot = mode().isHotspot();
        SegmentedTabs.paint(transportHotspot, hotspot);
        SegmentedTabs.paint(transportWifi, !hotspot);
    }

    /** The QR cards; the join card only on Hotspot (on Wi-Fi there is nothing to join). */
    private void showSections(boolean join) {
        status.setVisibility(View.GONE);
        joinRoot.setVisibility(join ? View.VISIBLE : View.GONE);
        receiveRoot.setVisibility(View.VISIBLE);
        paintTabs();
        numberSteps();
    }

    /** Number the steps the user sees: with the join step hidden (Wi-Fi transport), the invite is 1.
     *  The caption is Clone's one-code / two-code wording. */
    private void numberSteps() {
        boolean joinShown = joinRoot.getVisibility() == View.VISIBLE;
        QrSection.setStep(receiveRoot, joinShown ? "2" : "1", getString(R.string.k2go_cx_action_share));
        secReceive.caption.setText(joinShown ? R.string.k2go_clone_ready_scan2 : R.string.k2go_clone_ready_scan);
    }

    private void stopAndLeave() {
        ContentShareService.cancel(requireContext());
        goBack();
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
