/*
 * ============================================================================
 * Name        : QrActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : QR share content helper
 * ============================================================================
 */
package org.iiab.controller;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;


import java.util.List;

public class QrActivity extends AppCompatActivity {

    private TextView titleText;
    private TextView ipText;
    private ImageView qrImageView;
    private ImageButton btnFlip;
    private View cardContainer;

    private String wifiIp = null;
    private String hotspotIp = null;
    private boolean showingWifi = true; // Tracks which network is currently displayed

    // ADFA-4520: when launched with these extras, QrActivity renders a Wi-Fi
    // auto-join QR (WIFI: payload) plus visible credentials for the LocalOnlyHotspot,
    // instead of the IP-based content QR.
    public static final String EXTRA_WIFI_SSID = "extra_wifi_ssid";
    public static final String EXTRA_WIFI_PASS = "extra_wifi_pass";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr); // Rename your dialog_qr.xml to this

        titleText = findViewById(R.id.qr_network_title);
        ipText = findViewById(R.id.qr_ip_text);
        qrImageView = findViewById(R.id.qr_image_view);
        btnFlip = findViewById(R.id.btn_flip_qr);
        cardContainer = findViewById(R.id.qr_card_container);
        Button btnClose = findViewById(R.id.btn_close_qr);

        // Improve 3D perspective to avoid visual clipping during rotation
        float distance = 8000 * getResources().getDisplayMetrics().density;
        cardContainer.setCameraDistance(distance);

        btnClose.setOnClickListener(v -> finish());

        btnFlip.setOnClickListener(v -> {
            // Disable button during animation to prevent spam
            btnFlip.setEnabled(false);
            animateCardFlip();
        });

        // ADFA-4520: Wi-Fi auto-join mode (LocalOnlyHotspot credentials).
        String joinSsid = getIntent().getStringExtra(EXTRA_WIFI_SSID);
        String joinPass = getIntent().getStringExtra(EXTRA_WIFI_PASS);
        if (joinSsid != null) {
            btnFlip.setVisibility(View.GONE);
            showWifiJoin(joinSsid, joinPass);
            return;
        }

        // 1. Fetch real physical IPs with strict interface naming
        fetchNetworkInterfaces();

        // 2. Determine initial state and button visibility
        if (wifiIp != null && hotspotIp != null) {
            btnFlip.setVisibility(View.VISIBLE); // Both active, enable flipping
            showingWifi = true;
        } else if (wifiIp != null) {
            btnFlip.setVisibility(View.GONE);
            showingWifi = true;
        } else if (hotspotIp != null) {
            btnFlip.setVisibility(View.GONE);
            showingWifi = false;
        } else {
            // Fallback just in case they died between the MainActivity click and this onCreate
            finish();
            return;
        }

        updateQrDisplay();
    }

    /**
     * Performs a 3D flip animation. Swaps the data halfway through when the card is invisible.
     */
    private void animateCardFlip() {
        // Phase 1: Rotate out (0 to 90 degrees)
        ObjectAnimator flipOut = ObjectAnimator.ofFloat(cardContainer, "rotationY", 0f, 90f);
        flipOut.setDuration(200); // 200ms
        flipOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Card is edge-on (invisible). Swap the data!
                showingWifi = !showingWifi;
                updateQrDisplay();

                // Phase 2: Rotate in from the other side (-90 to 0 degrees)
                cardContainer.setRotationY(-90f);
                ObjectAnimator flipIn = ObjectAnimator.ofFloat(cardContainer, "rotationY", -90f, 0f);
                flipIn.setDuration(200);
                flipIn.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        btnFlip.setEnabled(true); // Unlock button
                    }
                });
                flipIn.start();
            }
        });
        flipOut.start();
    }

    /**
     * Updates the UI text and generates the new QR Code
     */
    private void updateQrDisplay() {
        String currentIp = showingWifi ? wifiIp : hotspotIp;
        String title = showingWifi ? getString(R.string.qr_title_wifi) : getString(R.string.qr_title_hotspot);

        // 8085 is the default port for the IIAB interface
        String url = "http://" + currentIp + ":8085/home";

        titleText.setText(title);
        ipText.setText(url);

        Bitmap qrBitmap = generateQrCode(url);
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap);
        }
    }

    /**
     * Strictly categorizes network interfaces to avoid Hotspot being labeled as Wi-Fi.
     */
    private void fetchNetworkInterfaces() {
        // EX3: single source of LAN IP discovery (shared with SyncFragment).
        org.iiab.controller.sync.transport.NetworkInterfaces.LanIps ips = org.iiab.controller.sync.transport.NetworkInterfaces.discover();
        wifiIp = ips.wifiIp;
        hotspotIp = ips.hotspotIp;
    }

    /**
     * ADFA-4520: renders a WPA Wi-Fi auto-join QR ("WIFI:S:...;T:WPA;P:...;;") plus
     * the credentials in text, so a guest can scan-to-join a LocalOnlyHotspot whose
     * SSID/password are auto-generated by the OS and cannot be chosen in advance.
     */
    private void showWifiJoin(String ssid, String pass) {
        String payload;
        if (pass != null && !pass.isEmpty()) {
            payload = "WIFI:T:WPA;S:" + escapeWifi(ssid) + ";P:" + escapeWifi(pass) + ";;";
        } else {
            payload = "WIFI:T:nopass;S:" + escapeWifi(ssid) + ";;";
        }
        titleText.setText(getString(R.string.qr_title_join_wifi));
        String creds = getString(R.string.qr_wifi_ssid, ssid);
        if (pass != null && !pass.isEmpty()) {
            creds = creds + "\n" + getString(R.string.qr_wifi_password, pass);
        }
        ipText.setText(creds);
        Bitmap qrBitmap = generateQrCode(payload);
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap);
        }
    }

    /** Escapes the WIFI: QR reserved characters: backslash, semicolon, comma, colon, quote. */
    private static String escapeWifi(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Generates a pure Black & White Bitmap using ZXing.
     */
    private Bitmap generateQrCode(String text) {
        // 800x800 guarantees high resolution on any screen (EX3: shared encoder).
        return org.iiab.controller.sync.transport.QrCodec.encode(text, 800);
    }
}
