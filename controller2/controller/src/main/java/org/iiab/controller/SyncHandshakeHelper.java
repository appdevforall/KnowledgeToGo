/*
 * ============================================================================
 * Name        : SyncHandshakeHelper.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Java wrapper for the native librsync.so binary
 * ============================================================================
 */
package org.iiab.controller;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONObject;

import java.security.SecureRandom;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class SyncHandshakeHelper {

    private static final String TAG = "IIAB-Handshake";

    public static class SyncCredentials {
        public String ip;
        public int port;
        public String user;
        public String pass;
        public boolean hasRootfs;
        public SyncCredentials(String ip, int port, String user, String pass, boolean hasRootfs) {
            this.ip = ip;
            this.port = port;
            this.user = user;
            this.pass = pass;
            this.hasRootfs = hasRootfs;
        }
    }

    public static String generateSecurePassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    public static String createPayload(String ip, int port, String user, String pass, boolean hasRootfs) {
        try {
            JSONObject json = new JSONObject();
            json.put("ip", ip);
            json.put("port", port);
            json.put("user", user);
            json.put("pass", pass);
            json.put("has_rootfs", hasRootfs);
            json.put("app", "iiab_sync");
            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error creating JSON payload", e);
            return "{}";
        }
    }

    public static SyncCredentials parsePayload(String scannedJson) {
        try {
            JSONObject json = new JSONObject(scannedJson);
            if (!json.has("app") || !json.getString("app").equals("iiab_sync")) {
                Log.w(TAG, "Scanned QR is not an IIAB Sync code.");
                return null;
            }
            return new SyncCredentials(
                    json.getString("ip"),
                    json.getInt("port"),
                    json.getString("user"),
                    json.getString("pass"),
                    json.optBoolean("has_rootfs", true) // Default to true if missing for legacy compatibility
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing scanned QR code", e);
            return null;
        }
    }

    public static Bitmap generateQrCode(String data, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR Code", e);
            return null;
        }
    }
}