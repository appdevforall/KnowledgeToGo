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

import org.appdevforall.k2go.deploy.domain.ExtractProgress;
import org.appdevforall.k2go.system.domain.TransferRate;
import org.appdevforall.k2go.util.ProcessRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

public final class BackupEngine {

    private static final String TAG = "IIAB-BackupEngine";

    private BackupEngine() {}

    /** K2GO-384: backup progress callback — percent 0..100 and ETA seconds (-1 when not measurable yet),
     *  from uncompressed bytes archived vs a pre-counted total. Emitted from the tar-stdout read loop. */
    public interface ProgressListener { void onProgress(int percent, long etaSeconds); }

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
     *
     * <p>K2GO-384: tar writes the <b>uncompressed</b> archive to stdout and the gzip is done here in Java
     * (over {@code dest}), so the bytes tar produces are observable and metered against the tree's total size
     * ({@code du -sb}). Byte progress tracks the gzip+write time far better than a member count, which
     * front-loads on the many small files and then stalls on the few large ones. When {@code listener} is
     * null or the pre-count fails, it streams without progress (indeterminate).
     *
     * <p>K2GO-384: {@code cancelled} (may be null) aborts the stream — the read loop kills tar and stops. The
     * caller distinguishes a cancel from a failure by that same flag and removes the incomplete SAF file.
     */
    public static boolean streamBackup(Context ctx, OutputStream dest, ProgressListener listener,
                                       AtomicBoolean cancelled) {
        File iiabRootDir = new File(ctx.getFilesDir(), "rootfs");
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File staticTar = new File(nativeDir, "libtar.so");
        String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";

        String manifestArg = stageIdentityManifest(ctx);   // "-C '<stage>' 'installed-rootfs/iiab/.k2go-rootfs.json' " or null

        // K2GO-384: total uncompressed bytes = the byte-accurate denominator. -1 => indeterminate fallback.
        final long totalBytes = (listener != null) ? countBytes(iiabRootDir) : -1L;

        // Single-quote the interpolated paths (robust if a path ever holds spaces/metacharacters). No "| gzip"
        // — Java compresses the stream below, which is what makes the uncompressed size observable.
        // --ignore-failed-read: iiab/sdcard (and other proot bind-mount stubs) are runtime mount points, not
        // rootfs content, and are unreadable with the box stopped ("Cannot open: Permission denied"). Skipping
        // them keeps tar's exit honest (0) instead of failing the whole backup on a file that must not be in
        // it. The old "tar | gzip" pipe hit the same read error but gzip's exit=0 silently masked tar's exit=2.
        String cmd = "'" + tarBin + "' -cf - --ignore-failed-read "
                + (manifestArg != null ? manifestArg : "")
                + "-C '" + iiabRootDir.getAbsolutePath() + "' installed-rootfs";

        Process p = null;
        Thread errDrain = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            // Drain stderr concurrently so a chatty tar cannot deadlock on a full pipe buffer. Keep a bounded
            // tail of it so a non-zero tar exit can report WHY (mirrors the restore surfacing tar's real cause,
            // ADFA-4544) instead of a bare "Backup failed".
            final Process fp = p;
            final StringBuilder errTail = new StringBuilder();
            errDrain = new Thread(() -> {
                try (BufferedReader er = new BufferedReader(new InputStreamReader(fp.getErrorStream()))) {
                    String l;
                    while ((l = er.readLine()) != null) {
                        synchronized (errTail) {
                            errTail.append(l).append('\n');
                            if (errTail.length() > 4096) errTail.delete(0, errTail.length() - 4096);
                        }
                    }
                } catch (Exception ignored) { }
            }, "backup-stderr");
            errDrain.start();

            // K2GO-384: gzip in Java over a close-guarded view of dest, metering the UNCOMPRESSED bytes tar
            // emits. The guard lets close() finish the gzip stream and free its Deflater WITHOUT closing dest,
            // which the caller owns and closes. System.nanoTime (not android SystemClock) keeps this domain
            // class off android.* and is monotonic for durations.
            final OutputStream guarded = new java.io.FilterOutputStream(dest) {
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                    out.write(b, off, len);   // FilterOutputStream's default writes byte-by-byte; delegate whole
                }
                @Override public void close() { /* caller owns dest */ }
            };
            try (GZIPOutputStream gz = new GZIPOutputStream(guarded);
                 InputStream in = p.getInputStream()) {
                byte[] buf = new byte[1 << 16];   // 64 KB
                long processed = 0L, lastEmitMs = 0L;
                final long startNs = System.nanoTime();
                int n;
                while ((n = in.read(buf)) > 0) {
                    // K2GO-384: cancel stops tar mid-stream (backup is read-only, so this is always safe).
                    if (cancelled != null && cancelled.get()) { p.destroy(); break; }
                    gz.write(buf, 0, n);
                    processed += n;
                    if (listener != null && totalBytes > 0L) {
                        long nowMs = (System.nanoTime() - startNs) / 1_000_000L;
                        if (nowMs - lastEmitMs >= 200L) {
                            lastEmitMs = nowMs;
                            long rate = TransferRate.perSecond(processed, nowMs);
                            listener.onProgress(ExtractProgress.percent(processed, totalBytes),
                                    ExtractProgress.etaSeconds(processed, totalBytes, rate));
                        }
                    }
                }
            }   // gz.close() writes the gzip trailer + ends the Deflater; the guard leaves dest open
            dest.flush();
            int exit = p.waitFor();
            if (errDrain != null) { try { errDrain.join(1500); } catch (InterruptedException ignored) { } }
            String tail; synchronized (errTail) { tail = errTail.toString().trim(); }
            final boolean userCancelled = cancelled != null && cancelled.get();
            if (exit != 0 && !userCancelled) {   // a cancel kills tar (non-zero) on purpose -- not a failure to log
                Log.w(TAG, "backup tar exited " + exit + (tail.isEmpty() ? "" : "; stderr tail:\n" + tail));
            } else if (exit == 0 && !tail.isEmpty()) {
                // K2GO-384: succeeded, but --ignore-failed-read let tar skip unreadable entries. Expected ones
                // are proot mount stubs (iiab/sdcard, ...); RECORD them so a genuinely dropped file is never
                // silent (the honesty this slice restored -- the old tar|gzip pipe masked all of this).
                Log.w(TAG, "backup ok; tar skipped unreadable entries:\n" + tail);
            }
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
     * K2GO-384: total uncompressed size (apparent bytes) of the tree tar will archive — the denominator for a
     * byte-accurate bar/ETA. {@code du -sb} is one fast metadata pass; it under-counts the uncompressed archive
     * only by tar's per-member header overhead (~a couple percent on a rootfs), which the {@code [0,99]} clamp
     * absorbs. Returns {@code -1} on any failure so the caller falls back to an indeterminate stream.
     */
    private static long countBytes(File iiabRootDir) {
        File tree = new File(iiabRootDir, "installed-rootfs");
        if (!tree.exists()) return -1L;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                    "du -sb '" + tree.getAbsolutePath() + "' 2>/dev/null | cut -f1"});
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                out = r.readLine();
            }
            p.waitFor();
            long n = (out != null) ? Long.parseLong(out.trim()) : -1L;
            return n > 0L ? n : -1L;
        } catch (Exception e) {
            Log.w(TAG, "byte pre-count failed: " + e.getMessage());
            return -1L;
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
