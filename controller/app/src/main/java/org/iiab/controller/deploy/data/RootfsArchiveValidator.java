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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * K2GO-372: the manifest's rejection, if any — a thin adapter over the pure rule in
     * {@link org.iiab.controller.deploy.domain.RootfsIdentity}, which is where the decision lives
     * and where it is tested. This half supplies the two things the rule cannot know on its own:
     * the running app's ABI, and how a domain verdict maps onto {@link Result}.
     *
     * <p>Worth calling before the listing pass: the manifest is packed first, so this reads a few
     * KB, while the listing reads the whole archive.
     *
     * @return the rejecting {@link Result}, or {@code null} when the manifest does not reject.
     */
    public static Result identityRejection(RootfsManifest.Identity id) {
        org.iiab.controller.deploy.domain.RootfsIdentity.Verdict v =
                org.iiab.controller.deploy.domain.RootfsIdentity.check(
                        id != null && id.present,
                        id == null ? null : id.kind,
                        id == null ? null : id.arch,
                        RootfsManifest.appAbiId());
        switch (v) {
            case NOT_A_ROOTFS: return Result.NOT_A_ROOTFS;
            case WRONG_ARCH:   return Result.WRONG_ARCH;
            default:           return null;
        }
    }

    /**
     * K2GO-372: the reason to show for a rejecting {@link Result}, or {@code null} when it does not
     * reject. Both extractor gates and the restore's pre-copy check spell the same verdicts, and they
     * used to each carry their own if-chain of strings.
     */
    public static String rejectionMessage(Context ctx, Result r) {
        if (r == Result.NOT_A_ROOTFS) return ctx.getString(org.iiab.controller.R.string.install_error_not_rootfs);
        if (r == Result.WRONG_ARCH) return ctx.getString(org.iiab.controller.R.string.install_error_wrong_arch);
        if (r == Result.CORRUPT) return ctx.getString(org.iiab.controller.R.string.install_error_corrupt);
        return null;
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
            Result rejection = identityRejection(id);
            if (rejection != null) {
                return rejection;
            }
            if (id.present) {
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
