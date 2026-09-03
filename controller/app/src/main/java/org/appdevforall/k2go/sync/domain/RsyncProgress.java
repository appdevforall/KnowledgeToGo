/*
 * ============================================================================
 * Name        : RsyncProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain parser for rsync stdout: a single --info=progress2 line
 *               (percent / speed / eta) and the --stats "Total transferred file
 *               size" line. Pure (no android.*, no I/O), so it is unit-testable
 *               on a plain JVM and clonable by an external host (Share-export,
 *               S14 step 1). Regexes are unchanged from the previous inline
 *               parsing in RsyncManager.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RsyncProgress {

    /** Bytes transferred so far this run — the leading column of a progress2 line. */
    public final long bytes;
    public final int percent;
    public final String speed;
    public final String eta;

    private RsyncProgress(long bytes, int percent, String speed, String eta) {
        this.bytes = bytes;
        this.percent = percent;
        this.speed = speed;
        this.eta = eta;
    }

    // ADFA-5160: capture the leading transferred-bytes column (with grouping separators)
    // as well as the percent. rsync's own percent divides by an estimate that grows as it
    // discovers files, so it jumps around; the byte count is a stable numerator the caller
    // can divide by a known total instead.
    private static final Pattern PROGRESS =
            Pattern.compile("([\\d,]+)\\s+(\\d+)%\\s+([\\d\\.]+[a-zA-Z/s]+)\\s+([\\d:]+)");

    private static final Pattern STATS =
            Pattern.compile("Total transferred file size:\\s+([\\d,\\.]+)\\s+bytes");

    /**
     * Parses one rsync {@code --info=progress2} line. Returns {@code null} if the
     * line carries no progress token or the percentage is not a number.
     */
    public static RsyncProgress parse(String line) {
        if (line == null) return null;
        Matcher m = PROGRESS.matcher(line);
        if (!m.find()) return null;
        try {
            long bytes = Long.parseLong(m.group(1).replaceAll("[,\\.]", ""));
            return new RsyncProgress(bytes, Integer.parseInt(m.group(2)), m.group(3), m.group(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the {@code --stats} "Total transferred file size: N bytes" line
     * (grouping separators stripped). Returns {@code fallback} when the line does
     * not match or the number cannot be parsed.
     */
    public static long parseTransferredBytes(String line, long fallback) {
        if (line == null) return fallback;
        Matcher m = STATS.matcher(line);
        if (!m.find()) return fallback;
        try {
            return Long.parseLong(m.group(1).replaceAll("[,\\.]", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Formats a duration in seconds as rsync's {@code H:MM:SS}. Negatives clamp to zero. */
    public static String formatEta(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h + String.format(java.util.Locale.US, ":%02d:%02d", m, s);
    }
}
