/*
 * ============================================================================
 * Name        : BoxImportStaging.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Bridges one SAF-read archive to a box-visible path the core can ingest.
 *               proot runs with -r <rootfs> and no separate bind for /library, so box /library maps
 *               to host <rootfs>/library (SystemStateEvaluator.rootfsDir = filesDir/rootfs/installed-rootfs/
 *               iiab). This writes the staged archive under host <rootfs>/library/.conex-staging and
 *               reports the matching box path /library/.conex-staging/<name> for POST /v1/import/item.
 *               The core default CONEX_STAGING_DIR is /library/.conex-staging (its is_within_dir guard).
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import android.content.Context;

import org.appdevforall.k2go.SystemStateEvaluator;
import org.appdevforall.k2go.contentexchange.domain.ExportFilenames;
import org.appdevforall.k2go.contentexchange.domain.PartialPayload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

public final class BoxImportStaging {

    /** The box-side import area, matching the core's default {@code CONEX_STAGING_DIR}. */
    private static final String BOX_STAGING_DIR = "/library/.conex-staging";
    private static final String HOST_STAGING_SUBPATH = "library/.conex-staging";

    private final File hostDir;

    public BoxImportStaging(Context context) {
        this.hostDir = new File(SystemStateEvaluator.rootfsDir(context), HOST_STAGING_SUBPATH);
    }

    /** The box-visible path for a staged file, as POST /v1/import/item expects it. */
    public static String boxPath(String storedName) {
        return BOX_STAGING_DIR + "/" + storedName;
    }

    /** A FAT-safe single-segment name for an untrusted drive filename (reuses the export rule). */
    public static String safeName(String name) {
        String safe = ExportFilenames.safeBaseName(name);
        return safe != null ? safe : "payload.bin";
    }

    /** Open the host staging file for writing, creating the staging directory if needed. */
    public OutputStream open(String storedName) throws IOException {
        return open(storedName, false);
    }

    /** Open the host staging file, appending to the kept bytes when {@code append} (a resume). */
    public OutputStream open(String storedName, boolean append) throws IOException {
        if (!hostDir.exists() && !hostDir.mkdirs()) {
            throw new IOException("Could not create the import staging folder");
        }
        return new FileOutputStream(new File(hostDir, storedName), append);
    }

    /** Bytes in a staged file, 0 when it does not exist. */
    public long length(String storedName) {
        return new File(hostDir, storedName).length();
    }

    /** Remove every kept partial whose name is not in {@code keep} (K2GO-577). */
    public void sweepPartials(Set<String> keep) {
        File[] files = hostDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (PartialPayload.isPartial(f.getName()) && !keep.contains(f.getName())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Remove a staged file after the core has ingested it (best effort). */
    public void remove(String storedName) {
        File f = new File(hostDir, storedName);
        if (f.exists()) {
            // The core moves/extracts from this path; a leftover copy only wastes space, so ignore a
            // failed delete rather than fail the import.
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
