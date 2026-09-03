/*
 * ============================================================================
 * Name        : BackupEngine.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4952. Streaming backup of the installed rootfs. Runs `tar -cf - installed-rootfs |
 *               gzip` and pipes the process stdout STRAIGHT to the caller's OutputStream (the SAF
 *               external file), so a multi-GB rootfs never needs a temp copy (no 2-3x disk). Stamps an
 *               identity manifest as the FIRST tar entry (origin=device-backup, no checksum) so a later
 *               restore recognizes it — ported from the old BackupController. Blocking; call off the main
 *               thread. The caller must have stopped the server first (a static rootfs) and holds the
 *               EnvironmentLock.
 * ============================================================================
 */
package org.appdevforall.k2go.backup.domain;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.util.ProcessRunner;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

public final class BackupEngine {

    private static final String TAG = "IIAB-BackupEngine";

    private BackupEngine() {}

    /**
     * Suggested external filename: {@code k2go_YYYY.DDD_<epochSecs>_<arch>.tar.gz}.
     *
     * <p>K2GO-377: the prefix follows the built rootfs artifacts onto the product's own name. It is
     * only ever a suggestion for the SAF picker — nothing reads it back, and a restore identifies an
     * archive by the manifest inside it, so backups written under the old name keep working.
     */
    public static String suggestedFileName(Context ctx) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int year = c.get(java.util.Calendar.YEAR);
        int day = c.get(java.util.Calendar.DAY_OF_YEAR);
        String abi = org.appdevforall.k2go.deploy.data.RootfsManifest.appAbiId();
        String arch = abi != null && abi.contains("64") ? "aarch64" : "armhf";
        return String.format(java.util.Locale.US, "k2go_%04d.%03d_%d_%s.tar.gz",
                year, day, System.currentTimeMillis() / 1000L, arch);
    }

    /**
     * Stream a gzip'd tar of {@code <filesDir>/rootfs/installed-rootfs} into {@code dest}. Returns true on
     * a clean tar exit. Does NOT close {@code dest} (the caller owns the SAF stream). Blocking.
     */
    public static boolean streamBackup(Context ctx, OutputStream dest) {
        File iiabRootDir = new File(ctx.getFilesDir(), "rootfs");
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File staticTar = new File(nativeDir, "libtar.so");
        File staticGzip = new File(nativeDir, "libgzip.so");
        String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";
        String gzipBin = staticGzip.exists() ? staticGzip.getAbsolutePath() : "gzip";

        String manifestArg = stageIdentityManifest(ctx);   // "-C '<stage>' 'installed-rootfs/iiab/.k2go-rootfs.json' " or null

        // Single-quote the interpolated paths (robust if a path ever holds spaces/metacharacters).
        String cmd = "'" + tarBin + "' -cf - "
                + (manifestArg != null ? manifestArg : "")
                + "-C '" + iiabRootDir.getAbsolutePath() + "' installed-rootfs | '" + gzipBin + "'";

        Process p = null;
        Thread errDrain = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            // Drain stderr concurrently so a chatty tar cannot deadlock on a full pipe buffer.
            final Process fp = p;
            errDrain = new Thread(() -> {
                try (InputStream es = fp.getErrorStream()) {
                    byte[] b = new byte[8192];
                    while (es.read(b) > 0) { /* discard */ }
                } catch (Exception ignored) { }
            }, "backup-stderr");
            errDrain.start();

            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[1 << 16];   // 64 KB
                int n;
                while ((n = in.read(buf)) > 0) {
                    dest.write(buf, 0, n);
                }
                dest.flush();
            }
            int exit = p.waitFor();
            if (exit != 0) Log.w(TAG, "backup tar|gzip exited " + exit);
            return exit == 0;
        } catch (Exception e) {
            Log.e(TAG, "streamBackup failed", e);
            if (p != null) p.destroy();
            return false;
        } finally {
            if (errDrain != null) { try { errDrain.join(1500); } catch (InterruptedException ignored) { } }
        }
    }

    /**
     * Stage {@code .k2go-rootfs.json} (origin=device-backup, no checksum — the phone is not a builder) in
     * a temp tree and return the extra {@code -C '<stage>' '<relpath>' } so it is packed FIRST, letting
     * RootfsArchiveValidator read identity from the first tar header without decompressing everything.
     * Returns null if staging failed (backup still proceeds, just without the manifest).
     */
    /**
     * Stage the identity manifest that becomes the archive's first tar entry.
     *
     * <p>K2GO-90: writes the {@code k2go-} name. Done now, while the installed base is developers
     * and a handful of users, because every backup written under the old name would otherwise join a
     * pile that has to age out before the tolerance for it can ever be dropped. Writing it now closes
     * that set today. The app reads both, so backups made before this still restore; the only thing
     * that cannot read one of these is a build older than v0.8.0, which is a downgrade across the
     * identity change and unsupported anyway.
     */
    private static String stageIdentityManifest(Context ctx) {
        File stageRoot = new File(ctx.getCacheDir(), "mfstage");
        try {
            if (stageRoot.exists()) ProcessRunner.run(new String[]{"rm", "-rf", stageRoot.getAbsolutePath()});
            File iiabStage = new File(stageRoot, "installed-rootfs/iiab");
            if (!iiabStage.mkdirs()) return null;
            String appAbi = org.appdevforall.k2go.deploy.data.RootfsManifest.appAbiId();
            String debArch = appAbi != null && appAbi.contains("64") ? "arm64" : "armhf";
            java.util.Calendar c = java.util.Calendar.getInstance();
            String built = String.format(java.util.Locale.US, "%04d.%03d",
                    c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.DAY_OF_YEAR));
            String json = "{\"schema\":1,\"kind\":\"k2go-rootfs\",\"arch\":\"" + appAbi
                    + "\",\"deb_arch\":\"" + debArch + "\",\"built\":\"" + built
                    + "\",\"builder\":\"knowledgetogo-app\",\"origin\":\"device-backup\"}";
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(iiabStage, ".k2go-rootfs.json"))) {
                o.write(json.getBytes("UTF-8"));
            }
            return "-C '" + stageRoot.getAbsolutePath() + "' 'installed-rootfs/iiab/.k2go-rootfs.json' ";
        } catch (Exception e) {
            Log.w(TAG, "Could not stage identity manifest: " + e.getMessage());
            return null;
        }
    }
}
