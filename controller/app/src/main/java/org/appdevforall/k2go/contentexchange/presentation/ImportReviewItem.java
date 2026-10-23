package org.appdevforall.k2go.contentexchange.presentation;

import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ImportVerdict;

/**
 * One bundle item paired with its receiver-side verdict, for the review screen. The {@code index} is
 * the item's position in the validated list and the selection key. Immutable.
 */
final class ImportReviewItem {

    private final int index;
    private final ImportEntry entry;
    private final ImportVerdict verdict;

    ImportReviewItem(int index, ImportEntry entry, ImportVerdict verdict) {
        this.index = index;
        this.entry = entry;
        this.verdict = verdict;
    }

    int index() {
        return index;
    }

    ImportEntry entry() {
        return entry;
    }

    ImportVerdict verdict() {
        return verdict;
    }

    /** Only an importable item (an app exists and it is not identical) can be picked. */
    boolean selectable() {
        return verdict.isImportable();
    }
}
