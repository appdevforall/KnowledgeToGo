package org.appdevforall.k2go.contentexchange.presentation;

import org.appdevforall.k2go.contentexchange.domain.ImportManifest;

import java.util.Collections;
import java.util.List;

/**
 * The review-screen state: reading / ready / error, the validated items, whether a manifest was found,
 * the names a scan could not recognize, and any read error. Immutable snapshot the fragment renders.
 */
final class ImportReviewUiState {

    enum Status { LOADING, READY, ERROR }

    final Status status;
    final List<ImportReviewItem> items;
    final boolean hadManifest;
    final List<String> unrecognized;
    final String error;

    private ImportReviewUiState(Status status, List<ImportReviewItem> items, boolean hadManifest,
                                List<String> unrecognized, String error) {
        this.status = status;
        this.items = items;
        this.hadManifest = hadManifest;
        this.unrecognized = unrecognized;
        this.error = error;
    }

    static ImportReviewUiState loading() {
        return new ImportReviewUiState(Status.LOADING, Collections.emptyList(), true,
                Collections.emptyList(), null);
    }

    static ImportReviewUiState error(String message) {
        return new ImportReviewUiState(Status.ERROR, Collections.emptyList(), true,
                Collections.emptyList(), message);
    }

    static ImportReviewUiState ready(List<ImportReviewItem> items, ImportManifest manifest) {
        return new ImportReviewUiState(Status.READY, items, manifest.hadManifest(),
                manifest.unrecognized(), null);
    }
}
