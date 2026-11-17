/*
 * ============================================================================
 * Name        : CloneLinkRows.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The two status rows of the Clone screen (K2GO-576): Wi-Fi and
 *               USB cable. The cable row follows CableSession. On the receiver,
 *               the Wi-Fi row checks every few seconds that the phone is on the
 *               sender's hotspot: the hotspot gateway is the sender, so it asks
 *               the gateway on the rsync port (no network scan, no location
 *               permission). On the sender, the row shows the hotspot state.
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
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.color.MaterialColors;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.hotspot.LocalHotspotManager;
import org.appdevforall.k2go.sync.cable.data.CableSession;
import org.appdevforall.k2go.sync.cable.domain.WifiLink;
import org.appdevforall.k2go.util.AppExecutors;
import org.appdevforall.k2go.util.ByteFormatter;

import java.net.InetSocketAddress;
import java.net.Socket;

public final class CloneLinkRows {

    private static final long CHECK_MS = 3000;

    private final View root;
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
    /** The sender's address from its pairing data (QR or cable), or null before pairing. */
    private volatile String senderHost;
    private volatile boolean probeOnce;
    private boolean inFlight;   // main thread only: one check at a time
    private final Runnable check = this::checkWifi;

    public CloneLinkRows(View root, LifecycleOwner owner) {
        this.root = root;
        wifiIcon = root.findViewById(R.id.k2go_link_wifi_icon);
        wifiDetail = root.findViewById(R.id.k2go_link_wifi_detail);
        wifiBadge = root.findViewById(R.id.k2go_link_wifi_badge);
        usbIcon = root.findViewById(R.id.k2go_link_usb_icon);
        usbDetail = root.findViewById(R.id.k2go_link_usb_detail);
        usbBadge = root.findViewById(R.id.k2go_link_usb_badge);
        CableSession.get().state().observe(owner, this::showCable);
    }

    public void setVisible(boolean visible) {
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Sender: the Wi-Fi row shows its own hotspot, or that it shares over the Wi-Fi network. */
    public void showSender(boolean hotspotMode, LocalHotspotManager.State hs) {
        stopChecks();
        boolean on = hs != null && hs.phase == LocalHotspotManager.Phase.ON;
        Context c = root.getContext();
        if (!hotspotMode) {
            // Over a Wi-Fi network: show its band; the receiver confirms that it reaches us.
            WifiLink.Band band = WifiLink.band(ownFrequency(c));
            wifiDetail.setText(R.string.k2go_clone_link_wifi_network);
            paint(wifiIcon, wifiBadge, band == WifiLink.Band.GHZ_5 ? WifiLink.Level.OK : WifiLink.Level.WARN,
                    bandText(c, band));
        } else if (on) {
            paint(wifiIcon, wifiBadge, WifiLink.Level.OK, c.getString(R.string.k2go_clone_link_badge_linked));
            wifiDetail.setText(c.getString(R.string.k2go_clone_link_hotspot_on, hs.clients));
        } else {
            paint(wifiIcon, wifiBadge, WifiLink.Level.WARN, c.getString(R.string.k2go_clone_link_badge_not_ready));
            wifiDetail.setText(R.string.k2go_clone_link_hotspot_off);
        }
    }

    /**
     * Receiver: checks the hotspot every few seconds until stopChecks(). With {@code probeSender}
     * false (during a transfer) it does not connect to the sender's daemon, which allows only a few
     * connections; it keeps the last answer and still follows Wi-Fi on/off and the band.
     */
    public void startChecks(int senderRsyncPort, boolean probeSender) {
        this.probeSender = probeSender;
        main.removeCallbacks(check);   // re-check now: the pairing or the transfer state changed
        if (checkPort == senderRsyncPort) { main.post(check); return; }
        if (checkPort == senderRsyncPort) return;
        checkPort = senderRsyncPort;
        main.removeCallbacks(check);
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
            boolean probe = probeSender || probeOnce;
            probeOnce = false;
            String host = senderHost;
            boolean answers = on && (probe ? senderAnswers(host != null ? host : gateway(wm.getDhcpInfo().gateway), port)
                    : lastAnswers);
            lastAnswers = answers;
            WifiLink.Band band = WifiLink.band(freq);
            main.post(() -> {
                inFlight = false;
                if (checkPort != port) return;
                showWifi(WifiLink.judge(on, answers, band), on, answers, band);
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

    private static String bandText(Context c, WifiLink.Band band) {
        return band == WifiLink.Band.GHZ_5 ? c.getString(R.string.k2go_clone_link_band_5)
                : band == WifiLink.Band.GHZ_2_4 ? c.getString(R.string.k2go_clone_link_band_24) : "";
    }

    private static boolean senderAnswers(String host, int port) {
        if (host == null) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showWifi(WifiLink.Level level, boolean on, boolean answers, WifiLink.Band band) {
        Context c = root.getContext();
        String bandText = band == WifiLink.Band.GHZ_5 ? c.getString(R.string.k2go_clone_link_band_5)
                : band == WifiLink.Band.GHZ_2_4 ? c.getString(R.string.k2go_clone_link_band_24) : null;
        if (!on) {
            wifiDetail.setText(R.string.k2go_clone_link_wifi_off);
            paint(wifiIcon, wifiBadge, level, c.getString(R.string.k2go_clone_link_badge_off));
        } else if (!answers) {
            // Paired over a Wi-Fi network: say "not found", not "not on its hotspot".
            wifiDetail.setText(senderHost != null ? R.string.k2go_clone_link_wifi_not_found
                    : R.string.k2go_clone_link_wifi_not_joined);
            paint(wifiIcon, wifiBadge, level, c.getString(R.string.k2go_clone_link_badge_not_ready));
        } else {
            wifiDetail.setText(band != WifiLink.Band.GHZ_5 ? R.string.k2go_clone_link_wifi_slow
                    : senderHost != null ? R.string.k2go_clone_link_wifi_found_network
                    : R.string.k2go_clone_link_wifi_found);
            paint(wifiIcon, wifiBadge, level, bandText != null ? bandText : "");
        }
    }

    private void showCable(CableSession.State s) {
        if (s == null) return;
        Context c = root.getContext();
        switch (s.link) {
            case LINKED:
                usbDetail.setText(s.bytesPerSec > 0
                        ? c.getString(R.string.k2go_clone_link_usb_active, ByteFormatter.toHuman(s.bytesPerSec) + "/s")
                        : c.getString(R.string.k2go_clone_link_usb_linked));
                paint(usbIcon, usbBadge, WifiLink.Level.OK, c.getString(R.string.k2go_clone_link_badge_linked));
                break;
            case WAITING:
                usbDetail.setText(R.string.k2go_clone_link_usb_waiting);
                paint(usbIcon, usbBadge, WifiLink.Level.WARN, c.getString(R.string.k2go_clone_link_badge_waiting));
                break;
            default:
                usbDetail.setText(R.string.k2go_clone_link_usb_none);
                paintNeutral(usbIcon, usbBadge, c.getString(R.string.k2go_clone_link_badge_optional));
        }
    }

    private void paint(ImageView icon, TextView badge, WifiLink.Level level, String text) {
        int color = ContextCompat.getColor(root.getContext(), level == WifiLink.Level.OK ? R.color.status_success
                : level == WifiLink.Level.WARN ? R.color.status_warning : R.color.status_danger);
        icon.setColorFilter(color);
        badge.setTextColor(color);
        badge.setText(text);
    }

    private void paintNeutral(ImageView icon, TextView badge, String text) {
        int color = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant);
        icon.setColorFilter(color);
        badge.setTextColor(color);
        badge.setText(text);
    }
}
