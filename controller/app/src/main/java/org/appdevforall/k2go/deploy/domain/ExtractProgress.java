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
 *               ADFA-5118 adds two pure rules for the unified verify+extract bar:
 *               - etaSeconds(done,total,rate): seconds left for a byte pass.
 *               - unifiedPercent(passPercent,secondPass): map a single pass onto
 *                 the two-pass [0,100] bar (verify = [0,50), extract = [50,100)).
 *               Pure JVM (no android.*) => unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

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

    /**
     * ADFA-5118: seconds remaining for a byte-based pass, or {@code -1} when it cannot be
     * honestly estimated yet — unknown total, nothing moved, or no rate (see TransferRate,
     * which returns 0 until it has enough elapsed time to be meaningful). {@code ratePerSec}
     * is bytes/second. Returns 0 once the pass has already moved everything.
     */
    public static long etaSeconds(long done, long total, long ratePerSec) {
        if (total <= 0L || done <= 0L || ratePerSec <= 0L) return -1L;
        long remaining = total - done;
        if (remaining <= 0L) return 0L;
        return remaining / ratePerSec;
    }

    /**
     * ADFA-5118: map a single pass's percent onto the unified two-pass bar. Verify owns
     * [0,50), extract owns [50,100). {@code passPercent} is that pass's own 0..100; it is
     * clamped, halved, and offset by 50 for the second pass. The result is capped at 99 so
     * only true completion (set by the caller) reaches 100. This keeps the bar monotone
     * across the handoff: verify ends near 49, extract starts at 50.
     */
    public static int unifiedPercent(int passPercent, boolean secondPass) {
        if (passPercent < 0) passPercent = 0;
        if (passPercent > 100) passPercent = 100;
        int v = (secondPass ? 50 : 0) + passPercent / 2;
        return v > 99 ? 99 : v;
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
        String name = slash >= 0 ? line.substring(slash + 1) : line;
        return isOpaque(name) ? "" : name;
    }

    /**
     * ADFA-5119: is this name a machine's business rather than a person's?
     *
     * <p>The detail line exists to give a sign of life during a long extraction, and a file name does
     * that only while it means something. A rootfs carries thousands of content-addressed blobs —
     * {@code 0418c83b80f7f7bfaec2738bfb…62196c0781702f6eddc8.body} was on screen during a real run —
     * and a 64-character hash gives movement without information. It also fills the whole line, so
     * the middle-ellipsized text swings to full width and back as ordinary names alternate with
     * hashes, which is the same restlessness ADFA-4910 removed from the percentage.
     *
     * <p>The rule is narrow on purpose: a long unbroken run of hex, which is what a digest looks like
     * and what an ordinary file name never does. Sixteen characters, because that is past any real
     * word and short of the shortest digest anyone uses. Anything else is shown — an unreadable name
     * we cannot recognise is better than silently hiding a legible one.
     */
    private static boolean isOpaque(String name) {
        int run = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (hex) {
                if (++run >= 16) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }
}
