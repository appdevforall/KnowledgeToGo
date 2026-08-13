/*
 * ============================================================================
 * Name        : ByteToken.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4895. Pure rule: aria2's "400MiB" / "4.5MiB" / "1.0GiB"
 *               tokens back into bytes. No Android, no I/O.
 * ============================================================================
 */
package org.iiab.controller.download.domain;

/**
 * Reads a size or rate token out of an aria2 progress line and gives back a number.
 *
 * <p><b>Why this exists.</b> aria2 prints everything we need on one line —
 * {@code [#2089b0 400MiB/1.0GiB(39%) CN:4 DL:4.5MiB ETA:2m20s]} — and until now the app captured
 * the rate only to concatenate a localized "/s" onto it and hand a display string upwards. A
 * string cannot be compared to a baseline, cannot be divided into a remaining size, and cannot be
 * decided on. The number has to survive the parse.
 *
 * <p><b>Binary and decimal are not the same and the difference is not academic.</b> aria2 emits
 * IEC units (KiB, MiB, GiB — powers of 1024) but the same field can carry SI units from other
 * tools, and at gigabyte scale treating GiB as GB is a 7% error — which lands directly in an
 * estimate the user is asked to make a decision on. Both tables are kept, and the {@code i} is
 * what selects between them.
 *
 * <p>Mirrors {@code parseRate} in {@code static/dashboard/sockets/kiwix.exec.ts}, deliberately:
 * the in-server downloader reads the same tokens from the same program. If one changes, change
 * both — the same standing coordination as the aria2 flag sets (ADFA-4832).
 */
public final class ByteToken {

    /** The token could not be read. Distinct from a real zero, which is a legitimate rate. */
    public static final long UNKNOWN = -1L;

    private static final long KI = 1024L;
    private static final long MI = KI * 1024L;
    private static final long GI = MI * 1024L;
    private static final long TI = GI * 1024L;

    private ByteToken() {
    }

    /**
     * Parse a token such as {@code 400MiB}, {@code 4.5MiB}, {@code 1.0GiB}, {@code 512K},
     * {@code 1.2MB} or a bare {@code 850} into bytes.
     *
     * <p>A missing unit means bytes, which is what aria2 does. Anything unreadable is
     * {@link #UNKNOWN} rather than zero: a download reporting nothing and a download reporting
     * genuinely no throughput are different facts, and only one of them means the transfer is in
     * trouble.
     */
    public static long parse(String token) {
        if (token == null) return UNKNOWN;
        String t = token.trim();
        if (t.isEmpty()) return UNKNOWN;

        int i = 0;
        while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.')) i++;
        if (i == 0) return UNKNOWN;

        double value;
        try {
            value = Double.parseDouble(t.substring(0, i));
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
        // No sign check: the scan above never consumes a '-', so a negative token has
        // already been rejected before we get here.
        if (Double.isNaN(value) || Double.isInfinite(value)) return UNKNOWN;

        String unit = t.substring(i).trim();
        // aria2 writes a trailing "/s" on some fields; the rate and the size share this parser.
        int slash = unit.indexOf('/');
        if (slash >= 0) unit = unit.substring(0, slash);
        unit = unit.toUpperCase(java.util.Locale.ROOT);

        long multiplier = multiplierFor(unit);
        if (multiplier == UNKNOWN) return UNKNOWN;

        double bytes = value * multiplier;
        if (bytes > Long.MAX_VALUE) return UNKNOWN;
        return Math.round(bytes);
    }

    /** {@code UNKNOWN} for a unit this does not recognise, so a typo is never read as bytes. */
    private static long multiplierFor(String unit) {
        switch (unit) {
            case "":
            case "B":   return 1L;
            case "K":
            case "KI":
            case "KIB": return KI;
            case "M":
            case "MI":
            case "MIB": return MI;
            case "G":
            case "GI":
            case "GIB": return GI;
            case "T":
            case "TI":
            case "TIB": return TI;
            case "KB":  return 1000L;
            case "MB":  return 1000_000L;
            case "GB":  return 1000_000_000L;
            case "TB":  return 1000_000_000_000L;
            default:    return UNKNOWN;
        }
    }
}
