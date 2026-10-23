package org.appdevforall.k2go.contentexchange.presentation;

/**
 * The review-screen selection totals and the phone-disk verdict: how many items and bytes are picked,
 * the device free space, and whether the selection fits. Immutable value the bottom bar reads.
 */
final class ImportSelectionSummary {

    final int count;
    final long bytes;
    final Long freeBytes;   // null when the phone free space could not be read
    final boolean fits;

    ImportSelectionSummary(int count, long bytes, Long freeBytes, boolean fits) {
        this.count = count;
        this.bytes = bytes;
        this.freeBytes = freeBytes;
        this.fits = fits;
    }

    boolean isEmpty() {
        return count == 0;
    }
}
