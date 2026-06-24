/*
 * ============================================================================
 * Name        : RootfsManifest.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Reads the identity manifest (installed-rootfs/iiab/.iiab-rootfs.json,
 *               packed FIRST) from a rootfs/backup tarball. Canonical contract:
 *               docs/ROOTFS_MANIFEST.md (schema 1). Soft phase: when present it
 *               authoritatively gates kind/arch; when absent the caller alerts
 *               and falls back to the legacy ELF/structure heuristic.
 * ============================================================================
 */
package org.iiab.controller.deploy.data;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Minimal, dependency-free reader for the identity manifest. The manifest is the
 * FIRST member of the tar (see {@code docs/ROOTFS_MANIFEST.md}), so we parse only
 * the first few 512-byte tar headers in Java — a few KB of decompressed input —
 * rather than shelling to {@code tar} (avoids relying on {@code --occurrence}
 * support in the bundled {@code libtar.so}).
 */
public final class RootfsManifest {

    private static final String TAG = "IIAB-RootfsManifest";
    private static final String MEMBER_SUFFIX = "iiab/.iiab-rootfs.json";
    private static final int MAX_HEADERS = 8;            // identity is first; scan a few in case of pax/dir entries
    private static final int MAX_JSON_BYTES = 64 * 1024;

    /** What the identity manifest tells us. {@code present=false} means none found. */
    public static final class Identity {
        public final boolean present;
        public final String kind;
        public final String arch;
        /** Producer hint; "device-backup" means an app-created backup with no checksum by design. */
        public final String origin;

        private Identity(boolean present, String kind, String arch, String origin) {
            this.present = present;
            this.kind = kind;
            this.arch = arch;
            this.origin = origin;
        }

        static Identity absent() {
            return new Identity(false, null, null, null);
        }
    }

    private RootfsManifest() {
        // Static utility; not instantiable.
    }

    /** The running app's ABI id, in the manifest's vocabulary. */
    public static String appAbiId() {
        return android.os.Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
    }

    /** Read the identity manifest from {@code archivePath}, or {@link Identity#absent()}. */
    public static Identity read(String archivePath) {
        boolean isGzip = archivePath.toLowerCase(Locale.US).endsWith(".gz");
        try (InputStream raw = new FileInputStream(archivePath);
             InputStream in = isGzip ? new GZIPInputStream(raw) : new BufferedInputStream(raw)) {

            byte[] header = new byte[512];
            for (int i = 0; i < MAX_HEADERS; i++) {
                if (!readFully(in, header, 512)) {
                    break;
                }
                if (isAllZero(header)) {
                    break; // end-of-archive marker
                }
                String name = cString(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                if (size < 0) {
                    break;
                }
                if (normalizeEndsWith(name, MEMBER_SUFFIX)) {
                    int toRead = (int) Math.min(size, MAX_JSON_BYTES);
                    byte[] json = new byte[toRead];
                    if (!readFully(in, json, toRead)) {
                        break;
                    }
                    return parse(new String(json, "UTF-8"));
                }
                // Skip this member's content, padded to a 512-byte boundary.
                long padded = ((size + 511) / 512) * 512;
                if (!skipFully(in, padded)) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read identity manifest: " + e.getMessage());
        }
        return Identity.absent();
    }

    private static Identity parse(String jsonText) {
        try {
            JSONObject o = new JSONObject(jsonText);
            String kind = o.optString("kind", null);
            String arch = o.optString("arch", null);
            String origin = o.optString("origin", null);
            return new Identity(true, kind, arch, origin);
        } catch (Exception e) {
            Log.w(TAG, "Identity manifest present but unparseable: " + e.getMessage());
            // Present-but-broken: treat as present with no usable fields so the
            // caller's kind check fails closed.
            return new Identity(true, null, null, null);
        }
    }

    private static boolean normalizeEndsWith(String rawName, String suffix) {
        if (rawName == null) {
            return false;
        }
        String n = rawName.replace('\\', '/');
        if (n.startsWith("./")) {
            n = n.substring(2);
        }
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        return n.equals("installed-rootfs/" + suffix) || n.endsWith("/" + suffix) || n.equals(suffix);
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        try {
            return new String(b, off, end - off, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long val = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (any) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                return -1;
            }
            val = (val << 3) + (c - '0');
            any = true;
        }
        return any ? val : 0;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buf, int n) throws java.io.IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) {
                return false;
            }
            off += r;
        }
        return true;
    }

    private static boolean skipFully(InputStream in, long n) throws java.io.IOException {
        long left = n;
        byte[] tmp = new byte[8192];
        while (left > 0) {
            int r = in.read(tmp, 0, (int) Math.min(tmp.length, left));
            if (r == -1) {
                return false;
            }
            left -= r;
        }
        return true;
    }
}
