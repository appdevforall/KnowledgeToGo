package org.appdevforall.k2go.contentexchange.domain;

/**
 * Gets the box storage summary for the manager's "total used / free disk" row.
 *
 * <p>Thin by design: the degrade rule ("could not read /v1/storage") lives in the
 * repository contract, which returns {@link StorageStatus#unavailable()} instead
 * of throwing. This use case never throws, so a missing storage endpoint never
 * fails the manager: the content still lists, only the free-disk figure is hidden.
 */
public final class GetStorageStatusUseCase {

    private final ContentRepository repository;

    public GetStorageStatusUseCase(ContentRepository repository) {
        this.repository = repository;
    }

    /** Never null; {@link StorageStatus#available()} is {@code false} when unreachable. */
    public StorageStatus execute() {
        StorageStatus status = repository.fetchStorage();
        return status != null ? status : StorageStatus.unavailable();
    }
}
