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

import java.net.InetAddress;
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
            wifiDetail.setText(R.string.k2go_clone_link_wifi_network);
            paintNeutral(wifiIcon, wifiBadge, "");
        } else if (on) {
            paint(wifiIcon, wifiBadge, WifiLink.Level.OK, c.getString(R.string.k2go_clone_link_badge_linked));
            wifiDetail.setText(c.getString(R.string.k2go_clone_link_hotspot_on, hs.clients));
        } else {
            paint(wifiIcon, wifiBadge, WifiLink.Level.WARN, c.getString(R.string.k2go_clone_link_badge_not_ready));
            wifiDetail.setText(R.string.k2go_clone_link_hotspot_off);
        }
    }

    /** Receiver: checks the hotspot every few seconds until stopChecks(). */
    public void startChecks(int senderRsyncPort) {
        if (checkPort == senderRsyncPort) return;
        checkPort = senderRsyncPort;
        main.removeCallbacks(check);
        main.post(check);
    }

    public void stopChecks() {
        checkPort = -1;
        main.removeCallbacks(check);
    }

    @SuppressWarnings("deprecation") // getConnectionInfo / getDhcpInfo: no permission needed for these fields
    private void checkWifi() {
        final int port = checkPort;
        if (port < 0) return;
        final Context app = root.getContext().getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            boolean on = wm != null && wm.isWifiEnabled();
            int freq = on ? wm.getConnectionInfo().getFrequency() : -1;
            boolean answers = on && senderAnswers(wm.getDhcpInfo().gateway, port);
            WifiLink.Band band = WifiLink.band(freq);
            main.post(() -> {
                if (checkPort != port) return;
                showWifi(WifiLink.judge(on, answers, band), on, answers, band);
                main.postDelayed(check, CHECK_MS);
            });
        });
    }

    private static boolean senderAnswers(int gatewayLe, int port) {
        if (gatewayLe == 0) return false;
        byte[] ip = {(byte) gatewayLe, (byte) (gatewayLe >> 8), (byte) (gatewayLe >> 16), (byte) (gatewayLe >> 24)};
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByAddress(ip), port), 800);
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
            wifiDetail.setText(R.string.k2go_clone_link_wifi_not_joined);
            paint(wifiIcon, wifiBadge, level, c.getString(R.string.k2go_clone_link_badge_not_ready));
        } else {
            wifiDetail.setText(band == WifiLink.Band.GHZ_5 ? R.string.k2go_clone_link_wifi_found
                    : R.string.k2go_clone_link_wifi_slow);
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
