/*
 * ============================================================================
 * Name        : CloneLinkRows.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The two status rows of the Clone screen (K2GO-576): Wi-Fi and
 *               USB cable. LinkRule picks the colour and the text (K2GO-584);
 *               this class collects the inputs and paints. On the receiver, the
 *               Wi-Fi row checks every few seconds whether the sender answers on
 *               the rsync port: the hotspot gateway before pairing, the paired
 *               address after (no network scan, no location permission). On the
 *               sender, the row shows its hotspot or its Wi-Fi network.
 *               Lifecycle: Clone binds it in onCreateView; the receiver checks
 *               stop with stopChecks() and with the view's lifecycle.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.presentation;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.color.MaterialColors;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.hotspot.LocalHotspotManager;
import org.appdevforall.k2go.sync.cable.data.CableSession;
import org.appdevforall.k2go.sync.cable.data.ShareLogWatch;
import org.appdevforall.k2go.sync.cable.data.UsbPresence;
import org.appdevforall.k2go.sync.cable.domain.LinkRule;
import org.appdevforall.k2go.sync.cable.domain.ShareSessions;
import org.appdevforall.k2go.sync.cable.domain.WifiLink;
import org.appdevforall.k2go.util.AppExecutors;

import java.net.InetSocketAddress;
import java.net.Socket;

public final class CloneLinkRows {

    private static final long CHECK_MS = 3000;

    private final View root;
    private final TextView wifiTitle;
    private final ImageView wifiIcon;
    private final TextView wifiDetail;
    private final TextView wifiBadge;
    private final ImageView usbIcon;
    private final TextView usbDetail;
    private final TextView usbBadge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int checkPort = -1;
    private volatile boolean probeSender = true;
    private volatile boolean lastAnswers;
    private volatile String lastProbed;   // the address the last answer is about
    /** The sender's address from its pairing data (QR or cable), or null before pairing. */
    private volatile String senderHost;
    private volatile boolean probeOnce;
    private boolean inFlight;   // main thread only: one check at a time
    private final Runnable check = this::checkWifi;

    // Main thread only: the last inputs of each row, so a change of one input repaints the row.
    private CableSession.State cable;
    private boolean cablePresent;        // a USB data cable is plugged in (system USB state)
    private Probe probe;                // receiver: the last Wi-Fi check, null before the first
    private boolean senderSide;
    private boolean hotspotMode;
    private LocalHotspotManager.State hotspot;
    private Boolean peerWifi;            // sender: the receiver's verdict over the cable, null when unknown
    private long wifiRate = -1;          // receiver: bytes/s on Wi-Fi during a pull, -1 when no pull runs
    private ShareSessions.WifiPeer wifiPeer = ShareSessions.WifiPeer.NONE;   // sender: from its daemon log

    /** One receiver Wi-Fi check. */
    private static final class Probe {
        final boolean on;
        final boolean onNetwork;
        final boolean answers;
        final boolean viaHotspot;
        final WifiLink.Band band;
        final String probed;   // the address the answer is about, null before any check

        Probe(boolean on, boolean onNetwork, boolean answers, boolean viaHotspot, WifiLink.Band band,
              String probed) {
            this.on = on;
            this.onNetwork = onNetwork;
            this.answers = answers;
            this.viaHotspot = viaHotspot;
            this.band = band;
            this.probed = probed;
        }
    }

    public CloneLinkRows(View root, LifecycleOwner owner) {
        this.root = root;
        wifiIcon = root.findViewById(R.id.k2go_link_wifi_icon);
        wifiTitle = root.findViewById(R.id.k2go_link_wifi_title);
        wifiDetail = root.findViewById(R.id.k2go_link_wifi_detail);
        wifiBadge = root.findViewById(R.id.k2go_link_wifi_badge);
        usbIcon = root.findViewById(R.id.k2go_link_usb_icon);
        usbDetail = root.findViewById(R.id.k2go_link_usb_detail);
        usbBadge = root.findViewById(R.id.k2go_link_usb_badge);
        CableSession.get().state().observe(owner, s -> {
            boolean wasLinked = cableLinked();
            cable = s;
            showCable();
            // The Wi-Fi row depends on whether the cable can carry the copy; the state repeats every second.
            if (cableLinked() != wasLinked) showWifi();
        });
        ShareLogWatch shareLog = new ShareLogWatch(root.getContext());
        shareLog.peer().observe(owner, p -> {
            wifiPeer = p;
            if (senderSide) showWifi();
        });
        UsbPresence presence = new UsbPresence(root.getContext());
        presence.present().observe(owner, p -> {
            cablePresent = Boolean.TRUE.equals(p);
            showCable();
            showWifi();   // the Wi-Fi text mentions the cable
        });
        owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(LifecycleOwner o) {
                presence.start();
                shareLog.start();
            }

            @Override
            public void onStop(LifecycleOwner o) {
                presence.stop();
                shareLog.stop();
            }
        });
    }

    public void setVisible(boolean visible) {
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Sender: the receiver's Wi-Fi verdict arrived over the cable. */
    public void showPeerWifi(Boolean reaches, boolean hotspotMode, LocalHotspotManager.State hs) {
        peerWifi = reaches;
        showSender(hotspotMode, hs);
    }

    /** Sender: the Wi-Fi row shows its own hotspot, or that it shares over the Wi-Fi network. */
    public void showSender(boolean hotspotMode, LocalHotspotManager.State hs) {
        stopChecks();
        senderSide = true;
        this.hotspotMode = hotspotMode;
        hotspot = hs;
        showWifi();
    }

    /**
     * Receiver: checks the Wi-Fi every few seconds until stopChecks(). With {@code probeSender}
     * false (during a transfer) it does not connect to the sender's daemon, which allows only a few
     * connections; it keeps the last answer and still follows Wi-Fi on/off and the band.
     */
    public void startChecks(int senderRsyncPort, boolean probeSender) {
        senderSide = false;
        this.probeSender = probeSender;
        main.removeCallbacks(check);   // re-check now: the pairing or the transfer state changed
        checkPort = senderRsyncPort;
        main.post(check);
    }

    /**
     * The sender's address once known (QR scan or cable). Then the row checks that address on the
     * rsync port, so it confirms a shared Wi-Fi network too, not only the sender's hotspot. One
     * check runs at once, also during the confirm step.
     */
    public void setSenderHost(String host) {
        if (host == null ? senderHost == null : host.equals(senderHost)) return;
        senderHost = host;
        probeOnce = host != null;
    }

    public void stopChecks() {
        checkPort = -1;
        main.removeCallbacks(check);
    }

    @SuppressWarnings("deprecation") // getConnectionInfo / getDhcpInfo: no permission needed for these fields
    private void checkWifi() {
        final int port = checkPort;
        if (port < 0 || inFlight) return;
        inFlight = true;
        final Context app = root.getContext().getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            boolean on = wm != null && wm.isWifiEnabled();
            int freq = on ? wm.getConnectionInfo().getFrequency() : -1;
            boolean probeNow = probeSender || probeOnce;
            probeOnce = false;
            String host = senderHost;
            String gw = on ? gateway(wm.getDhcpInfo().gateway) : null;
            boolean viaHotspot = host == null || host.equals(gw);   // the hotspot gateway is the sender
            String target = host != null ? host : gw;
            boolean answers = on && (probeNow ? senderAnswers(target, port) : lastAnswers);
            lastAnswers = answers;
            if (probeNow) lastProbed = on ? target : null;
            if (probeNow) CableSession.get().reportWifi(answers);   // the sender shows the same verdict
            Probe p = new Probe(on, gw != null, answers, viaHotspot, WifiLink.band(freq), lastProbed);
            main.post(() -> {
                inFlight = false;
                if (checkPort != port) return;
                probe = p;
                showWifi();
                main.postDelayed(check, CHECK_MS);
            });
        });
    }

    /** The hotspot gateway (the sender on its own hotspot), or null with no DHCP lease. */
    private static String gateway(int le) {
        if (le == 0) return null;
        return (le & 0xFF) + "." + ((le >> 8) & 0xFF) + "." + ((le >> 16) & 0xFF) + "." + ((le >> 24) & 0xFF);
    }

    @SuppressWarnings("deprecation")
    private static int ownFrequency(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled() ? wm.getConnectionInfo().getFrequency() : -1;
    }

    /** Same unit as the transfer speed under the progress bar (MB/s, 10^6). */
    private static String rate(long bytesPerSec) {
        return String.format(java.util.Locale.ROOT, "%.1fMB/s", bytesPerSec / 1e6);
    }

    private static boolean senderAnswers(String host, int port) {
        if (host == null) return false;
        try (Socket s = new Socket()) {
            // Phone to phone through a home router can take over a second (K2GO-576 tests).
            s.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Receiver, during a pull: what each path carries. A Wi-Fi path that moves data is proof that
     * the sender answers on Wi-Fi, so the row shows it even while the daemon probe is paused.
     */
    public void showRates(long[] rates) {
        wifiRate = rates == null ? -1 : rates[0];
        if (rates != null && rates[0] > 0) lastAnswers = true;
        if (!senderSide) showWifi();
    }

    private boolean cableLinked() {
        return cable != null && cable.link == LinkRule.Cable.LINKED;
    }

    private void showWifi() {
        Context c = root.getContext();
        if (senderSide) {
            WifiLink.Band band = hotspotMode ? WifiLink.Band.NONE : WifiLink.band(ownFrequency(c));
            int clients = hotspot != null ? hotspot.clients : -1;
            LinkRule.Row row = LinkRule.send(hotspotMode, hotspotPhase(hotspot), clients, band, peerWifi, cableLinked(),
                    wifiPeer);
            wifiTitle.setText(hotspotMode ? R.string.k2go_clone_link_wifi : R.string.k2go_wifi);
            paint(wifiIcon, wifiDetail, wifiBadge, row, band, clients, 0);
            return;
        }
        Probe p = probe;
        if (p == null) return;   // the first check has not answered yet
        boolean paired = senderHost != null;
        boolean carrying = wifiRate > 0;
        boolean checked = paired && senderHost.equals(p.probed);
        LinkRule.Row row = LinkRule.receive(p.on, p.onNetwork, paired, p.answers || carrying, p.viaHotspot,
                carrying, p.band, cableLinked(), checked);
        boolean hotspotTitle = p.viaHotspot && (paired || p.answers);
        wifiTitle.setText(hotspotTitle ? R.string.k2go_clone_link_wifi : R.string.k2go_wifi);
        paint(wifiIcon, wifiDetail, wifiBadge, row, p.band, 0, wifiRate);
    }

    private void showCable() {
        CableSession.State s = cable;
        if (s == null) return;
        LinkRule.Row row = LinkRule.cable(s.link, s.bytesPerSec > 0, s.done,
                cablePresent);
        paint(usbIcon, usbDetail, usbBadge, row, WifiLink.Band.NONE, 0, s.bytesPerSec);
    }

    private static LinkRule.Hotspot hotspotPhase(LocalHotspotManager.State hs) {
        if (hs == null) return LinkRule.Hotspot.OFF;
        if (hs.phase == LocalHotspotManager.Phase.ON) return LinkRule.Hotspot.ON;
        if (hs.phase == LocalHotspotManager.Phase.STARTING) return LinkRule.Hotspot.STARTING;
        return LinkRule.Hotspot.OFF;
    }

    private void paint(ImageView icon, TextView detail, TextView badge, LinkRule.Row row,
                       WifiLink.Band band, int clients, long bytesPerSec) {
        Context c = root.getContext();
        int color = row.tone == LinkRule.Tone.OK ? ContextCompat.getColor(c, R.color.status_success)
                : row.tone == LinkRule.Tone.ACTION ? ContextCompat.getColor(c, R.color.status_warning)
                : MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant);
        icon.setColorFilter(color);
        badge.setTextColor(color);
        badge.setText(badgeText(c, row.badge, band));
        detail.setText(row.text == LinkRule.Text.CARRYING ? c.getString(R.string.k2go_clone_link_usb_active, rate(bytesPerSec))
                : row.text == LinkRule.Text.HOTSPOT_JOINED ? c.getString(R.string.k2go_clone_link_hotspot_on, clients)
                // The cable is plugged in: do not ask the user to connect one.
                : row.text == LinkRule.Text.NOT_FOUND && cablePresent ? c.getString(R.string.k2go_clone_link_not_found_cable)
                : row.text == LinkRule.Text.CABLE_PEER ? c.getString(senderSide ? R.string.k2go_clone_link_usb_peer_send
                        : R.string.k2go_clone_link_usb_peer_receive)
                : c.getString(textRes(row.text)));
    }

    private static String badgeText(Context c, LinkRule.Badge b, WifiLink.Band band) {
        switch (b) {
            case BAND:
                return band == WifiLink.Band.GHZ_5 ? c.getString(R.string.k2go_clone_link_band_5)
                        : band == WifiLink.Band.GHZ_2_4 ? c.getString(R.string.k2go_clone_link_band_24) : "";
            case OFF: return c.getString(R.string.k2go_clone_link_badge_off);
            case NOT_READY: return c.getString(R.string.k2go_clone_link_badge_not_ready);
            case NOT_USED: return c.getString(R.string.k2go_clone_link_badge_not_used);
            case STARTING: return c.getString(R.string.k2go_clone_link_badge_starting);
            case READY: return c.getString(R.string.k2go_state_ready);
            case LINKED: return c.getString(R.string.k2go_clone_link_badge_linked);
            case WAITING: return c.getString(R.string.k2go_clone_link_badge_waiting);
            case OPTIONAL: return c.getString(R.string.k2go_clone_link_badge_optional);
            case DONE: return c.getString(R.string.k2go_done);
            default: return "";
        }
    }

    private static int textRes(LinkRule.Text t) {
        switch (t) {
            case WIFI_OFF: return R.string.k2go_clone_link_wifi_off_action;
            case NO_NETWORK: return R.string.k2go_clone_link_no_network;
            case CABLE_ONLY: return R.string.k2go_clone_link_cable_only;
            case NOT_ON_NETWORK: return R.string.k2go_clone_link_not_on_network;
            case NOT_CHECKED: return R.string.k2go_clone_link_not_checked;
            case PEER_ON_WIFI: return R.string.k2go_clone_link_peer_on_wifi;
            case PEER_DONE_WIFI: return R.string.k2go_clone_link_peer_done_wifi;
            case WAITING_CODE: return R.string.k2go_clone_link_waiting_code;
            case FOUND_HOTSPOT: return R.string.k2go_clone_link_wifi_found;
            case FOUND_NETWORK: return R.string.k2go_clone_link_wifi_found_network;
            case SLOW_BAND: return R.string.k2go_clone_link_wifi_slow;
            case NOT_FOUND: return R.string.k2go_clone_link_not_found_action;
            case HOTSPOT_STARTING: return R.string.k2go_clone_link_hotspot_starting;
            case HOTSPOT_OFF: return R.string.k2go_clone_link_hotspot_off;
            case HOTSPOT_ON: return R.string.k2go_clone_link_hotspot_ready;
            case HOTSPOT_WAITING: return R.string.k2go_clone_link_hotspot_waiting;
            case NETWORK_WAITING: return R.string.k2go_clone_link_network_waiting;
            case NETWORK_SHARING: return R.string.k2go_clone_link_wifi_network;
            case CABLE_NONE: return R.string.k2go_clone_link_usb_none;
            case CABLE_PRESENT: return R.string.k2go_clone_link_usb_present;
            case CABLE_SWITCHING: return R.string.k2go_clone_link_usb_switching;
            case CABLE_ASK: return R.string.k2go_clone_link_usb_ask;
            case CABLE_DENIED: return R.string.k2go_clone_link_usb_denied;
            case CABLE_LINKED: return R.string.k2go_clone_link_usb_linked;
            case CABLE_DONE: return R.string.k2go_clone_link_usb_done;
            default: throw new IllegalArgumentException(t.name());   // CARRYING, HOTSPOT_JOINED take an argument
        }
    }
}
