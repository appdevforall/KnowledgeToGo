package org.appdevforall.k2go.contentexchange.domain;

/**
 * Pure phone-disk preflight for the import selection: the crux of the disparate-storage problem, where
 * a small phone takes a subset of a big drive. The free figure is the phone's own {@code StatFs} view
 * (authoritative on K2Go, where the box is a subtree of the phone disk), read by the presentation layer
 * and passed in here.
 *
 * <p>The check is against the selected archive bytes. An extract-in-place app (Kiwix / Kolibri) needs
 * more transient room while the core unpacks the staged archive; that is the core's own {@code 507}
 * backstop, not modelled here, so this is a first-cut "will the copy fit" guard, not a guarantee.
 */
public final class ImportPreflight {

    private ImportPreflight() {}

    /**
     * Whether the selected bytes fit in the phone's free space. Unknown free space ({@code null})
     * returns {@code false}: a destructive-cost decision does not guess. A zero selection fits.
     */
    public static boolean fits(long selectedBytes, Long freeBytes) {
        if (selectedBytes <= 0L) {
            return true;
        }
        return freeBytes != null && selectedBytes <= freeBytes;
    }
}
