package org.appdevforall.k2go.contentexchange.domain;

/**
 * Pure rules for recovering items from a bundle that has no {@code manifest.json} (an export
 * interrupted before it wrote the index). Only files whose app can be told from the name alone are
 * recoverable: a Kiwix {@code .zim} carries its grouping keys in the filename convention
 * {@code <creator>_<lang>_<flavour>_<date>.zim}. A bare {@code .tar} (Kolibri / Calibre) gives no app
 * or identity in its name, so it is left for a real manifest.
 *
 * <p>Mirrors the core's {@code parse_zim_filename} so a scanned item validates the same way a manifest
 * item would. No UUID is read here (the scan has only names and sizes), so the synthesized dedup is
 * advisory (soft): never a confident authoritative match on a missing id. Stateless, unit-testable.
 */
public final class ImportScan {

    public static final String APP_KIWIX = "kiwix";

    private ImportScan() {}

    /** A date token like {@code 2023-10} or {@code 2023-10-01} at the tail of a Kiwix filename. */
    private static boolean isDate(String token) {
        return token != null && token.matches("\\d{4}-\\d{2}(-\\d{2})?");
    }

    /** The app a drive file belongs to by its name alone, or {@code null} when it cannot be told. */
    public static String recognizeApp(String name) {
        if (name == null) {
            return null;
        }
        return name.toLowerCase(java.util.Locale.US).endsWith(".zim") ? APP_KIWIX : null;
    }

    /**
     * Decompose a Kiwix filename into {@code [creator, lang, flavour, date]}, matching the core. The
     * flavour may span several underscore tokens; the date is the trailing dated token when present.
     * A name off the convention still yields a stable creator (the stem) with empty lang/flavour/date.
     */
    public static String[] parseZimFilename(String name) {
        String n = name != null ? name : "";
        String stem = n.toLowerCase(java.util.Locale.US).endsWith(".zim") ? n.substring(0, n.length() - 4) : n;
        String[] parts = stem.isEmpty() ? new String[0] : stem.split("_");
        int end = parts.length;
        String date = "";
        if (end > 0 && isDate(parts[end - 1])) {
            date = parts[end - 1];
            end--;
        }
        String creator = end > 0 ? parts[0] : stem;
        String lang = end > 1 ? parts[1] : "";
        StringBuilder flavour = new StringBuilder();
        for (int i = 2; i < end; i++) {
            if (flavour.length() > 0) {
                flavour.append('_');
            }
            flavour.append(parts[i]);
        }
        return new String[]{creator, lang, flavour.toString(), date};
    }
}
