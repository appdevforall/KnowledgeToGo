package org.appdevforall.k2go;

/**
 * Framework-free helpers for parsing system statistics.
 *
 * <p>These functions were extracted from {@code DashboardFragment} so they can be
 * unit-tested on the plain JVM (no Android dependencies, no emulator). Keep this
 * class free of any {@code android.*} imports.
 */
public final class SystemStatsUtil {

    private SystemStatsUtil() {
        // Utility class — no instances.
    }

    /**
     * Parses a single {@code /proc/meminfo} line and returns the numeric value (in kB).
     *
     * <p>A meminfo line looks like {@code "MemTotal:        8127200 kB"}. The value is the
     * second whitespace-separated token. Returns {@code 0} for any malformed or null input
     * rather than throwing, preserving the original defensive behavior.
     *
     * @param line a line from {@code /proc/meminfo}, e.g. {@code "MemAvailable:   123456 kB"}
     * @return the parsed value in kB, or {@code 0} if the line cannot be parsed
     */
    public static long parseMemLine(String line) {
        if (line == null) {
            return 0;
        }
        try {
            String[] parts = line.trim().split("\\s+");
            return Long.parseLong(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Maps an Android/Termux architecture string to the matching Debian architecture name.
     *
     * @param androidArch the Android architecture (e.g. {@code "aarch64"}, {@code "armv7l"})
     * @return the Debian architecture ({@code "arm64"}, {@code "armhf"}), {@code "N/A"} when
     *         unknown/empty, or the lower-cased input when no mapping applies
     */
    public static String getDebianArch(String androidArch) {
        if (androidArch == null || androidArch.equals("N/A")) {
            return "N/A";
        }
        String lower = androidArch.toLowerCase();

        if (lower.contains("arm64") || lower.contains("aarch64")) {
            return "arm64";
        }
        if (lower.contains("armeabi") || lower.contains("armv7")) {
            return "armhf";
        }

        return lower;
    }
}
