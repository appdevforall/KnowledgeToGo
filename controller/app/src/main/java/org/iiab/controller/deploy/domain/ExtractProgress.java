/*
 * ============================================================================
 * Name        : ExtractProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4915. Pure rules for the rootfs Extract phase UI:
 *               - percent(done,total): members_extracted / total, clamped [0,99]
 *                 (100 is reserved for completion, set explicitly by the caller).
 *               - firstLine(text): the first line of a multi-line label.
 *               - fileLabel(line): basename of a verbose tar line (files only;
 *                 "" for directory entries or empty input).
 *               Pure JVM (no android.*) => unit-testable.
 * ============================================================================
 */
package org.iiab.controller.deploy.domain;

public final class ExtractProgress {

    private ExtractProgress() { }

    /**
     * Percent of archive members extracted so far, clamped to [0,99].
     * Returns 0 when nothing is known yet (total unknown/empty or done<=0).
     * 99 is the cap during extraction; the caller sets 100 only on completion.
     */
    public static int percent(long done, long total) {
        if (total <= 0L || done <= 0L) return 0;
        long p = done * 100L / total;
        if (p < 0L) return 0;
        if (p > 99L) return 99;
        return (int) p;
    }

    /** First line of a possibly multi-line label, trimmed. Null-safe. */
    public static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i >= 0 ? s.substring(0, i) : s).trim();
    }

    /**
     * Basename of a verbose tar line: the segment after the last '/'.
     * Returns "" for empty input or a directory entry (line ends with '/'),
     * so the UI shows only files, never a bare directory path.
     */
    public static String fileLabel(String line) {
        if (line == null || line.isEmpty() || line.endsWith("/")) return "";
        int slash = line.lastIndexOf('/');
        return slash >= 0 ? line.substring(slash + 1) : line;
    }
}
