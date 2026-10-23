package org.appdevforall.k2go.contentexchange.presentation;

import org.appdevforall.k2go.contentexchange.domain.ManagedContent;
import org.appdevforall.k2go.contentexchange.domain.StorageStatus;

/**
 * Immutable UI state for the content manager, exposed by {@link ContentManagerViewModel}.
 *
 * <p>Presentation-layer type. Carries the loaded content (grouped) and the box
 * storage summary. {@code storage.available()} is {@code false} when the core has
 * no {@code /v1/storage} yet; the screen then hides the free-disk figure (it does
 * not go to {@link Status#ERROR} for that). {@link Status#ERROR} is only a real
 * content-load failure (box unreachable).
 */
public final class ContentManagerUiState {

    public enum Status {LOADING, SUCCESS, ERROR}

    public final Status status;
    public final ManagedContent content;
    public final StorageStatus storage;
    public final String error;

    private ContentManagerUiState(Status status, ManagedContent content, StorageStatus storage, String error) {
        this.status = status;
        this.content = content;
        this.storage = storage;
        this.error = error;
    }

    public static ContentManagerUiState loading() {
        return new ContentManagerUiState(Status.LOADING, null, null, null);
    }

    public static ContentManagerUiState success(ManagedContent content, StorageStatus storage) {
        return new ContentManagerUiState(Status.SUCCESS, content, storage, null);
    }

    public static ContentManagerUiState error(String message) {
        return new ContentManagerUiState(Status.ERROR, null, null, message);
    }
}
