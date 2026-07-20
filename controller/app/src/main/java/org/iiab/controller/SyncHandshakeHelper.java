/*
 * ============================================================================
 * Name        : SyncHandshakeHelper.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : P2P sync handshake: the QR payload schema (create/parse),
 * secure password generation, and QR encoding (delegated to QrCodec).
 * ============================================================================
 */
package org.iiab.controller;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONObject;

import org.iiab.controller.sync.domain.SyncCredentialValidator;

import java.security.SecureRandom;


public class SyncHandshakeHelper {

    private static final String TAG = "IIAB-Handshake";

    public static class SyncCredentials {
        public String ip;
        public int port;
        public String user;
        public String pass;
        public boolean hasRootfs;
        public int archBits;
        public long sysBytes;      // ADFA-4780: approx system size (0 = unknown)
        public long contentBytes;  // ADFA-4780: approx content size (0 = unknown)

        public SyncCredentials(String ip, int port, String user, String pass, boolean hasRootfs, int archBits) {
            this.ip = ip;
            this.port = port;
            this.user = user;
            this.pass = pass;
            this.hasRootfs = hasRootfs;
            this.archBits = archBits;
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

    public static String createPayload(String ip, int port, String user, String pass, boolean hasRootfs, int archBits) {
        return createPayload(ip, port, user, pass, hasRootfs, archBits, 0L, 0L);
    }

    /** ADFA-4780: adds the approximate system/content byte split (omitted when 0). */
    public static String createPayload(String ip, int port, String user, String pass, boolean hasRootfs, int archBits,
                                       long sysBytes, long contentBytes) {
        try {
            JSONObject json = new JSONObject();
            json.put("ip", ip);
            json.put("port", port);
            json.put("user", user);
            json.put("pass", pass);
            json.put("has_rootfs", hasRootfs);
            json.put("a", archBits);
            json.put("app", "iiab_sync");
            if (sysBytes > 0) json.put("sys_bytes", sysBytes);
            if (contentBytes > 0) json.put("content_bytes", contentBytes);
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
            String ip = json.getString("ip");
            int port = json.getInt("port");
            String user = json.getString("user");
            String pass = json.getString("pass");

            // S1: the QR payload is untrusted input that is later interpolated
            // into rsyncd.conf and a rsync:// URL. Reject anything that could
            // inject config directives or break out of the URL.
            SyncCredentialValidator.Result check =
                    SyncCredentialValidator.validateCredentials(ip, port, user, pass);
            if (!check.valid) {
                Log.w(TAG, "Rejecting scanned credentials: " + check.reason);
                return null;
            }

            SyncCredentials creds = new SyncCredentials(
                    ip,
                    port,
                    user,
                    pass,
                    json.optBoolean("has_rootfs", true), // Default to true if missing for legacy compatibility
                    json.optInt("a", 0)
            );
            creds.sysBytes = json.optLong("sys_bytes", 0L);          // ADFA-4780 (optional)
            creds.contentBytes = json.optLong("content_bytes", 0L);
            return creds;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing scanned QR code", e);
            return null;
        }
    }

    public static Bitmap generateQrCode(String data, int size) {
        // EX3: shared QR encoder.
        return org.iiab.controller.sync.transport.QrCodec.encode(data, size);
    }
}
