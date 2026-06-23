/*
 * ============================================================================
 * Name        : ApkVerifier.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Verifies a downloaded OTA APK is signed by the SAME certificate
 *               as the running app, before it is installed. Closes part of
 *               tech-debt F15 (no integrity verification of the update APK).
 * ============================================================================
 */
package org.iiab.controller.update.data;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import org.iiab.controller.update.domain.CertDigests;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads APK signing certificates via {@link PackageManager} (no private key,
 * no secrets — only public certs) and checks the downloaded update APK was
 * signed by the same certificate as the installed app.
 *
 * <p>This rejects a tampered/MITM'd APK (different signer) and a download that
 * is not even a validly-signed APK (e.g. an HTML/text error page → no signer →
 * empty set), so it must be called <strong>before</strong> launching the
 * installer.
 */
public final class ApkVerifier {

    private static final String TAG = "IIAB-ApkVerifier";

    private ApkVerifier() {
        // Static utility; not instantiable.
    }

    /** True only if {@code apkFile} is signed by the same certificate(s) as the running app. */
    public static boolean isSignedBySameCertAsApp(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            return false;
        }
        PackageManager pm = context.getPackageManager();
        Set<String> appDigests = digestsForInstalledApp(pm, context.getPackageName());
        Set<String> apkDigests = digestsForArchive(pm, apkFile.getAbsolutePath());
        boolean ok = CertDigests.sameSigner(appDigests, apkDigests);
        if (!ok) {
            Log.w(TAG, "Update APK signature does not match the running app (or it is not a signed APK).");
        }
        return ok;
    }

    private static Set<String> digestsForInstalledApp(PackageManager pm, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                return digestsFromSigningInfo(pi);
            }
            @SuppressWarnings("deprecation")
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            return digestsFromSignatures(pi == null ? null : pi.signatures);
        } catch (Exception e) {
            Log.e(TAG, "Could not read installed app signatures", e);
            return new HashSet<>();
        }
    }

    private static Set<String> digestsForArchive(PackageManager pm, String path) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES);
                return digestsFromSigningInfo(pi);
            }
            @SuppressWarnings("deprecation")
            PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES);
            return digestsFromSignatures(pi == null ? null : pi.signatures);
        } catch (Exception e) {
            Log.e(TAG, "Could not read APK archive signatures", e);
            return new HashSet<>();
        }
    }

    private static Set<String> digestsFromSigningInfo(PackageInfo pi) {
        if (pi == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || pi.signingInfo == null) {
            return new HashSet<>();
        }
        // Use the current APK content signers for both app and archive so the
        // sets are directly comparable.
        return digestsFromSignatures(pi.signingInfo.getApkContentsSigners());
    }

    private static Set<String> digestsFromSignatures(Signature[] sigs) {
        Set<String> out = new HashSet<>();
        if (sigs == null) {
            return out;
        }
        for (Signature s : sigs) {
            String d = sha256Hex(s.toByteArray());
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
