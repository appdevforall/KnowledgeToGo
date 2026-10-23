/*
 * ============================================================================
 * Name        : BoxImportStaging.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Bridges one SAF-read archive to a box-visible path the core can ingest.
 *               proot runs with -r <rootfs> and no separate bind for /library, so box /library maps
 *               to host <rootfs>/library (EnvironmentControl.rootfs = filesDir/rootfs/installed-rootfs/
 *               iiab). This writes the staged archive under host <rootfs>/library/.cx-staging and
 *               reports the matching box path /library/.cx-staging/<name> for POST /v1/import/item.
 *               The core default CX_STAGING_DIR is /library/.cx-staging (its is_within_dir guard).
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import android.content.Context;

import org.appdevforall.k2go.contentexchange.domain.ExportFilenames;
import org.appdevforall.k2go.env.EnvironmentControl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class BoxImportStaging {

    /** The box-side import area, matching the core's default {@code CX_STAGING_DIR}. */
    private static final String BOX_STAGING_DIR = "/library/.cx-staging";
    private static final String HOST_STAGING_SUBPATH = "library/.cx-staging";

    private final File hostDir;

    public BoxImportStaging(Context context) {
        this.hostDir = new File(EnvironmentControl.rootfs(context), HOST_STAGING_SUBPATH);
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
        if (!hostDir.exists() && !hostDir.mkdirs()) {
            throw new IOException("Could not create the import staging folder");
        }
        return new FileOutputStream(new File(hostDir, storedName));
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
