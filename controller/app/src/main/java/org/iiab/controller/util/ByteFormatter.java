package org.iiab.controller.util;

import java.util.Locale;

/**
 * Stateless helper for turning byte counts into human-readable text.
 *
 * <p>Uses binary units (GiB/MiB/KiB), consistent with how the app reports
 * device free space. Pure JVM logic — unit-testable without Android.
 */
public final class ByteFormatter {

    private static final long KIB = 1024L;
    private static final long MIB = KIB * 1024L;
    private static final long GIB = MIB * 1024L;

    private ByteFormatter() {
    }

    /** Bytes as a fractional number of GiB (e.g. 1_428_970_336 -> 1.331...). */
    public static double toGiB(long bytes) {
        return bytes / (double) GIB;
    }

    /** Human-readable string such as "1.3 GiB", "512 MiB" or "0 B". */
    public static String toHuman(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes >= GIB) {
            return String.format(Locale.US, "%.1f GiB", bytes / (double) GIB);
        }
        if (bytes >= MIB) {
            return String.format(Locale.US, "%.0f MiB", bytes / (double) MIB);
        }
        if (bytes >= KIB) {
            return String.format(Locale.US, "%.0f KiB", bytes / (double) KIB);
        }
        return bytes + " B";
    }
}
