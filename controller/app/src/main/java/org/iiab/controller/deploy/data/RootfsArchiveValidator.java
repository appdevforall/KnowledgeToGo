/*
 * ============================================================================
 * Name        : RootfsArchiveValidator.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Gate for imported/restored backups: is the tar archive a valid
 *               rootfs, and is it the SAME architecture as this app (ABI policy:
 *               ARM64<->ARM64, 32<->32)? Hard-blocks a positively-wrong arch.
 * ============================================================================
 */
package org.iiab.controller.deploy.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.deploy.domain.ElfClass;
import org.iiab.controller.deploy.domain.RootfsArchive;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Validates a {@code .tar.gz}/{@code .tar.xz} before it is imported or restored:
 * <ol>
 *   <li>structural sanity — it must look like a rootfs (rejects "imported a ZIM
 *       or a random file");</li>
 *   <li>architecture — if we can positively read an ELF binary inside, its class
 *       (32/64-bit) must match this app's ABI; otherwise it is hard-blocked.</li>
 * </ol>
 *
 * <p>Per the ABI-separation policy a definite architecture mismatch is blocked.
 * When the architecture cannot be determined (no probe binary, or the probed
 * member is a script/symlink) we do NOT block on arch (avoids false positives);
 * the structural check still applies.
 *
 * <p>Must run off the main thread (spawns {@code tar}).
 */
public final class RootfsArchiveValidator {

    private static final String TAG = "IIAB-RootfsValidator";

    public enum Result { OK, OK_NO_MANIFEST, OK_NO_CHECKSUM, NOT_A_ROOTFS, WRONG_ARCH, CORRUPT, UNREADABLE }

    private RootfsArchiveValidator() {
        // Static utility; not instantiable.
    }

    /** Validate from a file path (lists the archive itself; use for the import gate). */
    public static Result validate(Context context, String archivePath) {
        try {
            String tarBinary = resolveTar(context);
            boolean isGzip = archivePath.toLowerCase(Locale.US).endsWith(".gz");
            List<String> entries = listEntries(tarBinary, archivePath, isGzip);
            if (entries.isEmpty()) {
                return Result.UNREADABLE;
            }
            return validateWithEntries(context, archivePath, isGzip, tarBinary, entries, true);
        } catch (Exception e) {
            Log.e(TAG, "Validation error", e);
            return Result.UNREADABLE;
        }
    }

    /**
     * Validate when the caller already has the entry listing (e.g. {@code TarExtractor}
     * lists once for the D11 traversal guard — reuse it here, no second listing).
     */
    public static Result validateWithEntries(Context context, String archivePath,
                                             boolean isGzip, String tarBinary, List<String> entries) {
        // Restore re-uses the listing for the D11 guard; integrity was already
        // checked at import time, so don't pay a second full pass here.
        return validateWithEntries(context, archivePath, isGzip, tarBinary, entries, false);
    }

    /**
     * @param checkIntegrity when true (the import gate) and the rootfs is not an
     *        app-made backup, recompute the embedded iiab-tree-sha256-v1 treehash
     *        and fail closed ({@link Result#CORRUPT}) on a mismatch.
     */
    public static Result validateWithEntries(Context context, String archivePath,
                                             boolean isGzip, String tarBinary,
                                             List<String> entries, boolean checkIntegrity) {
        try {
            // Authoritative path: the build/app embeds an identity manifest
            // (installed-rootfs/iiab/.iiab-rootfs.json, packed first). See
            // docs/ROOTFS_MANIFEST.md. When present it decides kind + arch.
            RootfsManifest.Identity id = RootfsManifest.read(archivePath);
            if (id.present) {
                if (!"iiab-rootfs".equals(id.kind)) {
                    return Result.NOT_A_ROOTFS;
                }
                if (id.arch != null && !id.arch.isEmpty()
                        && !id.arch.equals(RootfsManifest.appAbiId())) {
                    return Result.WRONG_ARCH;
                }
                // Identity is authoritative for kind+arch. Now decide integrity.
                if ("device-backup".equals(id.origin)) {
                    // App-made backup: no checksum by design (we don't turn the
                    // phone into a builder). Cheap signal from the first header.
                    return Result.OK_NO_CHECKSUM;
                }
                if (!checkIntegrity) {
                    return Result.OK; // restore: already verified at import
                }
                RootfsIntegrity.Result ir = RootfsIntegrity.verify(archivePath);
                switch (ir.status) {
                    case MATCH:
                    case ABSENT:           // builder rootfs without integrity yet (soft phase)
                        return Result.OK;
                    case DECLARED_NONE:
                        return Result.OK_NO_CHECKSUM;
                    case MISMATCH:
                    case ERROR:
                    default:
                        Log.w(TAG, "Integrity check failed (" + ir.status + ") for " + archivePath);
                        return Result.CORRUPT;
                }
            }

            // Soft fallback (no manifest): legacy ELF/structure heuristic. We
            // return OK_NO_MANIFEST so the caller can surface a "manifest not
            // found" alert (non-blocking) for this first version.
            if (!RootfsArchive.looksLikeRootfs(entries)) {
                return Result.NOT_A_ROOTFS;
            }
            String probe = RootfsArchive.pickBinaryEntry(entries);
            if (probe == null) {
                return Result.OK_NO_MANIFEST; // structurally a rootfs; cannot probe arch
            }
            byte[] header = readMemberHeader(tarBinary, archivePath, isGzip, probe, 8);
            int cls = ElfClass.of(header);
            if (cls == ElfClass.UNKNOWN) {
                return Result.OK_NO_MANIFEST; // probed member wasn't a plain ELF -> arch undetermined
            }
            int want = android.os.Process.is64Bit() ? ElfClass.BITS_64 : ElfClass.BITS_32;
            return (cls == want) ? Result.OK_NO_MANIFEST : Result.WRONG_ARCH;
        } catch (Exception e) {
            Log.e(TAG, "Validation (with entries) error", e);
            return Result.UNREADABLE;
        }
    }

    private static String resolveTar(Context context) {
        File staticTar = new File(context.getApplicationInfo().nativeLibraryDir, "libtar.so");
        return staticTar.exists() ? staticTar.getAbsolutePath() : "/system/bin/tar";
    }

    private static List<String> listEntries(String tarBinary, String archivePath, boolean isGzip) throws Exception {
        List<String> names = new ArrayList<>();
        List<String> cmd = new ArrayList<>();
        cmd.add(tarBinary);
        if (isGzip) {
            cmd.add("-t");
            cmd.add("-f");
            cmd.add("-");
        } else {
            cmd.add("-tf");
            cmd.add(archivePath);
        }
        Process p = new ProcessBuilder(cmd).start();
        Thread feeder = isGzip ? startGzipFeeder(archivePath, p.getOutputStream()) : null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                names.add(line);
            }
        }
        p.waitFor();
        if (feeder != null) {
            feeder.join();
        }
        return names;
    }

    private static byte[] readMemberHeader(String tarBinary, String archivePath, boolean isGzip,
                                           String member, int n) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(tarBinary);
        cmd.add("-x");
        cmd.add("-O");
        cmd.add("-f");
        cmd.add(isGzip ? "-" : archivePath);
        cmd.add(member);
        Process p = new ProcessBuilder(cmd).start();
        Thread feeder = isGzip ? startGzipFeeder(archivePath, p.getOutputStream()) : null;

        byte[] buf = new byte[n];
        int got = 0;
        try (InputStream is = p.getInputStream()) {
            int r;
            while (got < n && (r = is.read(buf, got, n - got)) != -1) {
                got += r;
            }
        }
        p.destroy(); // we only need the header; let tar stop
        if (feeder != null) {
            feeder.join(300);
        }
        if (got < 5) {
            return null;
        }
        byte[] out = new byte[got];
        System.arraycopy(buf, 0, out, 0, got);
        return out;
    }

    private static Thread startGzipFeeder(String archivePath, OutputStream os) {
        Thread t = new Thread(() -> {
            try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(archivePath))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = gis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            } catch (Exception ignored) {
                // broken pipe once we stop reading is expected
            } finally {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        });
        t.start();
        return t;
    }
}
