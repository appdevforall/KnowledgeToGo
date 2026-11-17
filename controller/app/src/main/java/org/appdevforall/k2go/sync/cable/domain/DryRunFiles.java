/*
 * ============================================================================
 * Name        : DryRunFiles.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads the file list of an rsync dry-run that ran with
 *               OUT_FORMAT (size and name per line, after a fixed tag), so the
 *               transfer can be cut into chunks (K2GO-576). Pure JVM.
 *               Format escapes: https://download.samba.org/pub/rsync/rsyncd.conf.5#log_format
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class DryRunFiles {

    private static final String TAG = "K2F ";
    /** rsync --out-format value: tag, file length, file name. */
    public static final String OUT_FORMAT = TAG + "%l %n";

    private DryRunFiles() {
    }

    /** One entry from a tagged line, or null for any other line and for directories. */
    public static TransferChunk.FileEntry parse(String line) {
        if (line == null || !line.startsWith(TAG)) return null;
        int sp = line.indexOf(' ', TAG.length());
        if (sp < 0) return null;
        String name = line.substring(sp + 1);
        if (name.isEmpty() || name.endsWith("/")) return null;
        try {
            long size = Long.parseLong(line.substring(TAG.length(), sp));
            return size < 0 ? null : new TransferChunk.FileEntry(name, size);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
