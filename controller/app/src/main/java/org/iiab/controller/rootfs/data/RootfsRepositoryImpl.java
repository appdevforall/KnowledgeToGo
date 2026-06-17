package org.iiab.controller.rootfs.data;

import org.iiab.controller.rootfs.domain.Rootfs;
import org.iiab.controller.rootfs.domain.RootfsAbi;
import org.iiab.controller.rootfs.domain.RootfsRepository;
import org.iiab.controller.rootfs.domain.RootfsTier;

/**
 * Data-layer implementation of the domain {@link RootfsRepository} port.
 *
 * <p>Maps the raw size (bytes) from {@link RootfsRemoteDataSource} into the
 * {@link Rootfs} domain entity, and exposes the hardcoded fallback from
 * {@link RootfsCatalog}. The decision of <em>when</em> to use the fallback lives
 * in the domain use case, not here.
 */
public class RootfsRepositoryImpl implements RootfsRepository {

    private final RootfsRemoteDataSource remote;
    private final RootfsCatalog catalog;

    public RootfsRepositoryImpl(RootfsRemoteDataSource remote, RootfsCatalog catalog) {
        this.remote = remote;
        this.catalog = catalog;
    }

    @Override
    public Rootfs fetchLive(RootfsTier tier, RootfsAbi abi) {
        String url = catalog.metaUrl(tier, abi);
        long bytes = remote.fetchSizeBytes(url);
        return new Rootfs(tier, abi, url, bytes, bytes > 0);
    }

    @Override
    public Rootfs fallback(RootfsTier tier, RootfsAbi abi) {
        return new Rootfs(tier, abi, catalog.metaUrl(tier, abi), catalog.fallbackBytes(tier, abi), false);
    }
}
